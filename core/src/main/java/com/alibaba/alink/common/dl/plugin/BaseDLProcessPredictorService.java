package com.alibaba.alink.common.dl.plugin;

import com.alibaba.alink.common.dl.exchange.BytesDataExchange;
import com.alibaba.alink.common.dl.plugin.DLPredictServiceMapper.PredictorConfig;
import com.alibaba.alink.common.io.plugin.TemporaryClassLoaderContext;
import com.alibaba.alink.common.utils.JsonConverter;
import com.alibaba.alink.operator.common.pytorch.ListSerializer;
import com.alibaba.flink.ml.util.ShellExec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import static com.alibaba.alink.operator.common.pytorch.TorchScriptConstants.LIBRARY_PATH_KEY;

/**
 * Base class for predictor service using separate process for prediction. Current requires usage of
 * {@link PredictorConfig}.
 */
public abstract class BaseDLProcessPredictorService<T> implements DLPredictorService {
	private static final Logger LOG = LoggerFactory.getLogger(BaseDLProcessPredictorService.class);

	private FutureTask <Void> futureTask;

	private BytesDataExchange bytesDataExchange;

	private File inQueueFile;
	private File outQueueFile;
	// The launched process needs to delete this file to indicate it is ready to receive data.
	private File procReadyFile;

	private String libraryPath;
	private Integer intraOpParallelism;
	private ListSerializer listSerializer;

	private PredictorConfig config;

	public abstract Class <T> getPredictorClass();

	public static FutureTask <Void> startInferenceProcessWatcher(Process process) {
		Thread inLogger = new Thread(
			new ShellExec.ProcessLogger(process.getInputStream(), new ShellExec.StdOutConsumer()));
		Thread errLogger = new Thread(
			new ShellExec.ProcessLogger(process.getErrorStream(), new ShellExec.StdOutConsumer()));
		inLogger.setName("JavaInferenceProcess-in-logger");
		inLogger.setDaemon(true);
		errLogger.setName("JavaInferenceProcess-err-logger");
		errLogger.setDaemon(true);
		inLogger.start();
		errLogger.start();
		FutureTask <Void> res = new FutureTask <>(() -> {
			try {
				int r = process.waitFor();
				inLogger.join();
				errLogger.join();
				if (r != 0) {
					throw new RuntimeException("Java inference process exited with " + r);
				}

				LOG.info("Java inference process finished successfully");
			} catch (InterruptedException var8) {
				LOG.info("Java inference process watcher interrupted, killing the process");
			} finally {
				process.destroyForcibly();
			}

		}, null);
		Thread t = new Thread(res);
		t.setName("JavaInferenceWatcher");
		t.setDaemon(true);
		t.start();
		return res;
	}

	public static Process launchInferenceProcess(Class <?> predictorClass,
												 String outQueueFilename, String inQueueFilename,
												 String procReadyFilename, PredictorConfig config,
												 String libraryPath, Integer numThreads)
		throws IOException {
		List <String> args = new ArrayList <>();
		String javaHome = System.getProperty("java.home");
		args.add(String.join(File.separator, javaHome, "bin", "java"));
		// set classpath
		List <String> cpElements = new ArrayList <>();
		// add sys classpath
		cpElements.add(System.getProperty("java.class.path"));
		// add user code classpath
		if (Thread.currentThread().getContextClassLoader() instanceof URLClassLoader) {
			for (URL url : ((URLClassLoader) Thread.currentThread().getContextClassLoader()).getURLs()) {
				cpElements.add(url.toString());
			}
		}
		args.add("-cp");
		args.add(String.join(File.pathSeparator, cpElements));

		args.add("-Djava.library.path=" + libraryPath);
		args.add(ProcessPredictorRunner.class.getCanonicalName());
		args.add(predictorClass.getCanonicalName());
		// swapped in and out
		args.add(outQueueFilename);
		args.add(inQueueFilename);
		args.add(JsonConverter.toJson(config));
		args.add(procReadyFilename);

		LOG.info("Java Inference Cmd: " + String.join(" ", args));
		System.out.println(String.join(" ", args));
		ProcessBuilder builder = new ProcessBuilder(args);
		builder.environment().put("OMP_NUM_THREADS", String.valueOf(numThreads));
		builder.redirectErrorStream(true);
		builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
		return builder.start();
	}

	private FutureTask <Void> createInferFutureTask() {
		if (null != futureTask) {
			return futureTask;
		}
		Process process;
		try {
			process = launchInferenceProcess(getPredictorClass(),
				outQueueFile.getAbsolutePath(),
				inQueueFile.getAbsolutePath(),
				procReadyFile.getAbsolutePath(),
				config, libraryPath, intraOpParallelism);
		} catch (IOException e) {
			throw new RuntimeException("Launch inference process failed.", e);
		}
		return startInferenceProcessWatcher(process);
	}

	private FutureTask <Void> createInferFutureTaskDebug() {
		FutureTask <Void> res = new FutureTask <>(() -> {
			ProcessPredictorRunner.main(new String[] {getPredictorClass().getCanonicalName(),
				outQueueFile.getAbsolutePath(),
				inQueueFile.getAbsolutePath(),
				JsonConverter.toJson(config),
				procReadyFile.getAbsolutePath()});
		}, null);
		Thread t = new Thread(res);
		t.setContextClassLoader(this.getClass().getClassLoader());
		t.setName("JavaInference");
		t.setDaemon(true);
		t.start();
		return res;
	}

	private void destroyInferFutureTask(FutureTask <Void> inferFutureTask) {
		try {
			inferFutureTask.get();
		} catch (InterruptedException e) {
			LOG.error("Interrupted waiting for server join {}.", e.getMessage());
			inferFutureTask.cancel(true);
		} catch (ExecutionException e) {
			throw new RuntimeException("Inference process exited with exception.", e);
		}
	}

	@Override
	public void open(Map <String, Object> m) {
		config = PredictorConfig.fromMap(m);
		libraryPath = (String) m.get(LIBRARY_PATH_KEY);
		intraOpParallelism = config.intraOpNumThreads;
		listSerializer = new ListSerializer();

		try {
			inQueueFile = File.createTempFile("queue-", ".input");
			outQueueFile = File.createTempFile("queue-", ".output");
			procReadyFile = File.createTempFile("queue-", ".ready");
		} catch (IOException e) {
			throw new RuntimeException("Failed to create in/out queue files", e);
		}
		try {
			bytesDataExchange = new BytesDataExchange(inQueueFile.getAbsolutePath(), outQueueFile.getAbsolutePath());
		} catch (Exception e) {
			throw new RuntimeException("Failed to create BytesDataBridge", e);
		}

		try (TemporaryClassLoaderContext ignored = TemporaryClassLoaderContext.of(this.getClass().getClassLoader())) {
			futureTask = config.threadMode ? createInferFutureTaskDebug() : createInferFutureTask();
		}
		while (procReadyFile.exists()) {
			LOG.info("Waiting procReadyFile to be deleted.");
			try {
				if (futureTask.isDone()) {
					futureTask.get();
				}
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
			} catch (ExecutionException e) {
				throw new RuntimeException("Exception thrown in inference process.", e);
			}
		}
		if (procReadyFile.exists()) {
			throw new RuntimeException("ProcReadyFile for inference process still exists."
				+ "Inference process launched error.");
		}
	}

	@Override
	public void close() {
		if (null != bytesDataExchange) {
			bytesDataExchange.markWriteFinished();
			try {
				bytesDataExchange.close();
			} catch (IOException e) {
				LOG.info("Close BytesDataBridge failed, ignore.", e);
			}
		}
		if (null != inQueueFile) {
			//noinspection ResultOfMethodCallIgnored
			inQueueFile.delete();
		}
		if (null != outQueueFile) {
			//noinspection ResultOfMethodCallIgnored
			outQueueFile.delete();
		}
		destroyInferFutureTask(futureTask);
	}

	@Override
	public List <?> predict(List <?> inputs) {
		byte[] bytes = listSerializer.serialize(inputs);
		try {
			bytesDataExchange.write(bytes);
		} catch (IOException e) {
			throw new RuntimeException("Failed to write to data exchange.", e);
		}
		bytes = null;
		while (null == bytes) {
			try {
				if (futureTask.isDone()) {
					futureTask.get();
				}
				bytes = bytesDataExchange.read(false);
			} catch (IOException e) {
				throw new RuntimeException("Failed to read from data exchange.", e);
			} catch (ExecutionException | InterruptedException e) {
				throw new RuntimeException("Exception thrown in inference process.", e);
			}
		}
		return listSerializer.deserialize(bytes);
	}

	@Override
	public List <List <?>> predictRows(List <List <?>> inputs, int batchSize) {
		throw new UnsupportedOperationException("Not supported batch inference yet.");
	}
}