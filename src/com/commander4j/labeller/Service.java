package com.commander4j.labeller;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.commander4j.util.JUtility;

public class Service
{

	public Server server;
	LabellerUtility utils = new LabellerUtility();
	public Boolean shutdown = false;
	Logger logger = org.apache.logging.log4j.LogManager.getLogger((Service.class));

	/**
	 * How long a graceful stop is given before the JVM is allowed to exit regardless.
	 *
	 * <p>A labeller does not notice its shutdown flag instantly — mid-print it can be waiting
	 * on the printer — and {@link Server#stopLabeller} waits for the thread with no deadline.
	 * Without a bound here a service stop could block until the service manager lost patience
	 * and killed the process anyway, which is a worse outcome than giving up cleanly and
	 * saying so in the log.
	 */
	private static final long SHUTDOWN_TIMEOUT_MS = 20000;

	/** Guards against stopping twice when the hook and the normal exit path both run. */
	private final AtomicBoolean stopping = new AtomicBoolean(false);

	public Service()
	{

	}

	/**
	 * Attaches a JVM shutdown hook that stops the labellers, and the web viewer, before the
	 * process dies.
	 *
	 * <p>install4j runs this class as a service launcher with no stop class configured, so a
	 * service stop — like a Ctrl-C or a SIGTERM from the start script — arrives as an ordinary
	 * JVM shutdown. Without this hook none of the teardown in {@link Server#stop} runs at all.
	 *
	 * <p>Log4j2's own shutdown hook must be disabled at launch with
	 * {@code -Dlog4j2.shutdownHookEnabled=false} (it is, in both start scripts and the
	 * install4j launchers). Otherwise it tears the appenders down while this hook is still
	 * working and every shutdown message is lost — which is why closing the logging context is
	 * this hook's very last act.
	 */
	private static void attachShutdownHook(final Server server, final Logger logger, final AtomicBoolean stopping)
	{
		Runtime.getRuntime().addShutdownHook(new Thread(() -> stopServer(server, logger, stopping), "Shutdown"));

		logger.info("Shutdown hook active.");
	}

	/**
	 * Stops the server once, whichever path reaches it first. The stop itself runs on its own
	 * thread so it can be waited for with a deadline; see {@link #SHUTDOWN_TIMEOUT_MS}.
	 */
	private static void stopServer(final Server server, final Logger logger, final AtomicBoolean stopping)
	{
		if (server == null)
		{
			return;
		}

		if (stopping.compareAndSet(false, true) == false)
		{
			return;
		}

		logger.info("Shutdown detected - requesting threads stop.");

		Thread stopper = new Thread(() -> server.stop(0), "Shutdown-stop");
		stopper.setDaemon(true);
		stopper.start();

		try
		{
			stopper.join(SHUTDOWN_TIMEOUT_MS);
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
		}

		if (stopper.isAlive())
		{
			logger.warn("Threads still stopping after " + String.valueOf(SHUTDOWN_TIMEOUT_MS / 1000) + " seconds - exiting anyway.");
		}
		else
		{
			logger.info("All threads stopped.");
		}

		// Log4j2 last, so that every line above actually reaches the log file.
		LogManager.shutdown();
	}

	public Integer start(String[] args)
	{
		logger.info("Service - start");
		logger.info("Labeller Version : " + Labeller.version);

		server = new Server();

		attachShutdownHook(server, logger, stopping);

		server.start();

		while (shutdown.equals(false))
		{
			utils.pause(1000);
		}

		stop(0);

		return 0;
	}

	public int stop(int exitCode)
	{
		logger.info("Service - stop");

		stopServer(server, logger, stopping);

		return exitCode;
	}

	public static void main(String[] args)
	{

		LabellerUtility utils = new LabellerUtility();

		JUtility.initLogging("");
		Logger logger = org.apache.logging.log4j.LogManager.getLogger((Service.class));

		logger.info("Starting");
		logger.info("Labeller Version : " + Labeller.version);

		Server server = new Server();
		AtomicBoolean stopping = new AtomicBoolean(false);

		// Attached before the server starts, so a stop arriving during startup is handled too.
		attachShutdownHook(server, logger, stopping);

		server.start();

		while (server.started == false)
		{
			utils.pause(100);
		}

		while (server.isAlive())
		{
			utils.pause(100);
		}

		stopServer(server, logger, stopping);

		System.exit(0);
	}
}
