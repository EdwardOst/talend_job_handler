package com.talend.se.lambda.handler;

import java.io.File;
import java.io.IOException;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;

import com.amazonaws.services.lambda.runtime.LambdaLogger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

public class TalendJobHandler implements RequestStreamHandler {

	private static final String TALEND_JOB_HANDLER_ENV_CONTEXT_FILES = "TalendContextFiles";
	private static final String LOG4J2_CONFIGURATION_FILE_PROPERTY = "log4j2.configurationFile";
	static final String TALEND_JOB_HANDLER_ENV_CONFIG_FILE="TalendJobHandler_Log4j2ConfigFile";
	static final String TALEND_JOB_HANDLER_ENV_DEBUG="TalendJobHandler_Debug";
	private static final String TALEND_JOB_HANDLER_ENV_CLASS_NAME = "TalendJobClassName";
	
	static boolean debugTalendJobHandler = false;
	
	private static void initLog(String message) {
		if (debugTalendJobHandler) {
			System.out.println("initLog: " + message);
		}
	}
	
	static {
		debugTalendJobHandler = Arrays.asList( "on", "ON", "true", "TRUE", "1" ).contains(System.getenv(TALEND_JOB_HANDLER_ENV_DEBUG));
		
		// get the location of the log4j2 config file from the TALEND_JOB_HANDLER_ENV_CONFIG_FILE environment variable.
		// Try to load it as a classpath resource.  If that does not work try to load it as a file uri.

		String log4j2_config = System.getenv().get(TALEND_JOB_HANDLER_ENV_CONFIG_FILE);
		initLog("env[" + TALEND_JOB_HANDLER_ENV_CONFIG_FILE + "]=" + log4j2_config);
		if (log4j2_config != null) {
			URL log4j2_configUrl = TalendJobHandler.class.getClassLoader().getResource(log4j2_config);
			if (log4j2_configUrl != null) {
				try {
					URI log4j2_configUri = log4j2_configUrl.toURI();
					initLog("Resolved log4j2_configUrl '" + log4j2_config + "' to '" + log4j2_configUri + "'");
					File log4j2_configFile = new File(log4j2_configUri);
					String log4j_configPath = log4j2_configFile.getPath();
					if (log4j2_configFile.exists()) {
						initLog("log4j2File '" + log4j_configPath + "' exists");
						System.setProperty(LOG4J2_CONFIGURATION_FILE_PROPERTY, log4j2_configFile.toURI().toString());
					} else {
						initLog("log4jFile '" + log4j_configPath + "' does not exist");
					}
				} catch (URISyntaxException e) {
					e.printStackTrace();
				}
			} else {
				initLog("Could not resolve log4j2_configUrl '" + log4j2_config + "'");
				File log4j2File = new File(log4j2_config);
				initLog("log4j2File=" + log4j2File.getPath());
				if (log4j2File.exists()) {
					initLog("log4j2File exists");
					System.setProperty(LOG4J2_CONFIGURATION_FILE_PROPERTY, log4j2File.toURI().toString());
				} else {
					initLog("log4jFile does not exist");
				}
			}
		}
	}
	
	public TalendJobHandler() {
	}

	public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {

		LambdaLogger logger;
		logger = Optional.ofNullable(context.getLogger())
						.orElse(
								new LambdaLogger() {
									private Logger logger = LogManager.getLogger(TalendJobHandler.class);

									@Override
									public void log(String message) {
										logger.warn(message);
									}
								}
								);
		
		logger.log("System.java.class.path = " + System.getProperty("java.class.path"));
		logger.log( classloader_to_string( this.getClass().getClassLoader() ) );

		Map<String, String> env = System.getenv();

		String talendJobClassName = env.get(TALEND_JOB_HANDLER_ENV_CLASS_NAME);
		if (talendJobClassName == null || "".equals(talendJobClassName)) {
			throw new Error("TalendJobClassName environment variable is missing or empty.");
		}

		String talendContextFiles = env.get(TALEND_JOB_HANDLER_ENV_CONTEXT_FILES);
		List<String> talendContextList = null;
		if (talendContextFiles != null) {
			talendContextList = Arrays.asList(talendContextFiles.split("(:|;)"));
		}

		LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		Configuration config = ctx.getConfiguration();
		String log4j2_config_source = config.getConfigurationSource().getLocation();
		logger.log("log4j2_config_source=" + log4j2_config_source);
		logger.log(new String(Files.readAllBytes(Paths.get(log4j2_config_source)), StandardCharsets.UTF_8));
		
		try (final CloseableThreadContext.Instance ctc = CloseableThreadContext.push(
						Optional.ofNullable(System.getenv("AWS_REGION")).orElse("null") + ", "
						+ Optional.ofNullable(System.getenv("AWS_LAMBDA_FUNCTION_NAME")).orElse("null") + ", "
						+ Optional.ofNullable(System.getenv("AWS_LAMBDA_FUNCTION_VERSION")).orElse("null") + " : ")
				) {
			invokeTalendJob(talendJobClassName, talendContextList, logger::log, input, output, context);
		};
	}

	/**
	 * invokeTalendJob
	 * 
	 * Use reflection to get an instance of the job and then invoke runJob method.
	 * Bind the input and output streams received from the RequestStreamHandler
	 * interface to context variables.
	 * 
	 * @param talendJobClassName
	 *            - fully qualified class name of the job to run
	 * @param contextFiles
	 *            - a delimited list of context file path names to read
	 * @param input
	 *            - assigned to the job inputStream context variable
	 * @param output
	 *            - assigned to the job outputStream context variable
	 * @param context
	 *            - AWS Lambda context is assigned to the job awsContext context
	 *            variable
	 * @throws Error
	 */
	private void invokeTalendJob(String talendJobClassName, List<String> contextFiles, Consumer<String> logger, InputStream input,
			OutputStream output, Context context) throws Error {
		Object talendJob;
		Map<String, Object> parentContextMap;
		Method runJobMethod;
		String errorMessage = "Error in error handling code: errorMessage not initialized.";
		try {
			Class<?> jobClass = Class.forName(talendJobClassName);
			errorMessage = "Could not find default constructor for class '" + talendJobClassName + "'.";
			Constructor<?> ctor = jobClass.getConstructor();
			talendJob = ctor.newInstance();

			parentContextMap = readContextFiles(contextFiles, talendJob);
			parentContextMap.put("inputStream", input);
			parentContextMap.put("outputStream", output);
			parentContextMap.put("awsContext", context);

			errorMessage = "Could not find runJob method for class '" + talendJobClassName + "'.";
			runJobMethod = talendJob.getClass().getMethod("runJob", String[].class);

			logger.accept("invoking method runJob on class " + talendJob.getClass().getName());
			runJobMethod.invoke(talendJob, new Object[] { new String[] {} });
		} catch (ClassNotFoundException e) {
			throw new Error("Class for TalendJob '" + talendJobClassName + "' not found.", e);
		} catch (NoSuchMethodException e) {
			throw new Error(errorMessage, e);
		} catch (InstantiationException e) {
			throw new Error("Error instantiating '" + talendJobClassName + "'.", e);
		} catch (IllegalAccessException e) {
			throw new Error("Access error instantiating " + talendJobClassName + ".", e);
		} catch (InvocationTargetException e) {
			throw new Error("Error invoking default constructor for " + talendJobClassName + ".", e);
		}
	}

	private Map<String, Object> readContextFiles(List<String> contextFiles, Object talendJob) throws Error {

		Class<?> talendJobClass = talendJob.getClass();
		Map<String, Object> parentContextMap;
		parentContextMap = getParentContextMap(talendJob);

		if (contextFiles != null) {
			for (String contextFile : contextFiles) {
				URL contextUrl = talendJobClass.getResource(contextFile);
				if (contextUrl != null) {
					try {
						loadParentContext(parentContextMap, contextUrl.openStream());
					} catch (IOException e) {
						throw new Error("Error opening Talend context resource '" + contextUrl.toString() + "'");
					}
				}
			}
		}

		return parentContextMap;
	}

	private Map<String, Object> getParentContextMap(Object talendJob) throws Error {
		Map<String, Object> parentContextMap;

		try {
			Field parentContextMapField = talendJob.getClass().getField("parentContextMap");
			parentContextMap = ((Map<String, Object>) parentContextMapField.get(talendJob));
		} catch (NoSuchFieldException e) {
			throw new Error("Could not find parentContextMap field in class " + talendJob.getClass().getName() + ".",
					e);
		} catch (IllegalAccessException e) {
			throw new Error("Access error instantiating " + talendJob.getClass().getName() + ".", e);
		}
		return parentContextMap;
	}

	private void loadParentContext(Map<String, Object> parentContextMap, InputStream contextStream) {
		// BufferedReader reader = new BufferedReader(new InputStreamReader(contextStream));
		Properties defaultProps = new Properties();
		// must use the java.util.Properties.load() here to escape the string correctly.
		// for some reason the value is also escaped when persisted, although this does
		// not seem to be part of the spec.
		// so an entry with key name mykey and value with a string containing an equals
		// sign such as 'myparam=some_value'
		//     mykey=myparam=some_value
		// unnecessarily escapes the second = sign
		// mykey=myparam\=some_value
		try {
			defaultProps.load(contextStream);
			Set<String> keys = defaultProps.stringPropertyNames();
			for (String key : keys) {
				parentContextMap.put(key, defaultProps.getProperty(key));
			}
		} catch (IOException e) {
			throw new Error("Error reading context stream into parentContextMap", e);
		}
	}

	private String classpath_to_string(ClassLoader classLoader) {

		return Arrays.stream( ((URLClassLoader) classLoader).getURLs() )
				.map(url -> url.getFile())
				.collect(Collectors.joining(",", "[", "]"));
	}

	private String classloader_to_string(final ClassLoader classLoader) {
		
		Iterator<ClassLoader> classLoaderIterator = new Iterator<ClassLoader>() {

			private ClassLoader loader = classLoader;
			
			@Override
			public boolean hasNext() {
				return loader.getParent() != null;
			}

			@Override
			public ClassLoader next() {
				ClassLoader current = loader;
				loader = loader.getParent();
				return current;
			}
			
		};
		
		Iterable<ClassLoader> iterable = () -> classLoaderIterator;
		
		Stream<ClassLoader> classLoaders = StreamSupport.stream(iterable.spliterator(), false);
		return classLoaders
				.map( loader -> classpath_to_string(loader) )
				.collect(Collectors.joining(",", "[", "]"));
	}

}
