package com.commander4j.labeller.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.commander4j.labeller.Labeller;
import com.commander4j.labeller.LabellerProperties;
import com.commander4j.labeller.Server;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Embedded HTTP front end built on the JDK's {@code com.sun.net.httpserver} (zero extra
 * jars), ported from {@code util_modbusBridge}. It exists so an operator can watch a
 * labeller from a browser instead of needing an account on the server to read
 * {@code logs/c4jLabelServer.log}.
 *
 * <p><b>Read-only.</b> Nothing here can start, stop or print anything — the endpoints
 * observe and no more. That is deliberate: the port sits on a factory LAN, and a viewer
 * that cannot fire a label is safe to leave reachable.
 *
 * <p><b>Never fatal.</b> Labelling is the product; this is diagnostics. {@link Server}
 * treats a failure to bind as a logged warning and carries on, so a port clash costs the
 * viewer and not the production line.
 *
 * <p>The executor is a daemon-threaded cached pool: the default executor pins one handler
 * thread per open connection, which deadlocks once SSE holds a connection open. Daemon
 * threads also keep a non-signal exit from hanging on the pool.
 */
public final class WebServer
{
	private static final Logger log = LogManager.getLogger(WebServer.class);
	private static final Path WEB_ROOT = Path.of("web").toAbsolutePath().normalize();

	/** Matches {@code fileName} in {@code xml/config/log4j2.xml}. */
	private static final Path LOG_FILE = Path.of("logs", "c4jLabelServer.log");
	/** Default backlog for {@code /api/log/tail} when no {@code ?lines=} is given. */
	private static final int LOG_TAIL_DEFAULT = 200;

	private final Server server;
	private final String host;
	private final int port;
	private final HttpServer http;

	private final SseHub logHub;
	private final LogTailer logTailer;

	public WebServer(Server server, String host, int port) throws IOException
	{
		this.server = server;
		this.host = host;
		this.port = port;

		this.logHub = new SseHub("log", null);
		this.logTailer = new LogTailer(LOG_FILE, logHub);

		http = HttpServer.create(new InetSocketAddress(host, port), 0);
		http.setExecutor(Executors.newCachedThreadPool(daemonFactory()));
		http.createContext("/api/status", routeSafely(this::handleStatus));
		http.createContext("/api/log/tail", routeSafely(this::handleLogTail));
		// SSE handlers bypass routeSafely (which would close the exchange) and hold the
		// handler thread for the connection's lifetime.
		http.createContext("/events/log", sseHandler(logHub));
		http.createContext("/", routeSafely(this::handleStatic));
	}

	public void start()
	{
		http.start();
		logTailer.start();
		log.info("Web server listening on http://" + host + ":" + port + "/");
	}

	public void stop()
	{
		// Unblock SSE handler threads and stop the tailer (it reads the log) BEFORE the
		// caller stops log4j2, so teardown lines still reach the file.
		logHub.stop();
		logTailer.stop();
		http.stop(0);
		log.info("Web server stopped");
	}

	// --- routing ------------------------------------------------------------------------

	@FunctionalInterface
	private interface Route
	{
		void handle(HttpExchange ex) throws Exception;
	}

	/** Wraps a route so any uncaught exception becomes a 500 instead of a dropped socket. */
	private com.sun.net.httpserver.HttpHandler routeSafely(Route route)
	{
		return ex ->
		{
			try (ex)
			{
				route.handle(ex);
			}
			catch (Exception e)
			{
				log.warn("Handler error for " + ex.getRequestMethod() + " " + ex.getRequestURI() + " : " + e.toString());
				trySendError(ex, 500, "internal error");
			}
		};
	}

	/**
	 * Handler for an SSE stream. Deliberately does <em>not</em> use {@link #routeSafely}:
	 * its try-with-resources would close the exchange immediately, ending the stream.
	 * {@link SseHub#register} keeps the connection open and closes the exchange itself.
	 */
	private com.sun.net.httpserver.HttpHandler sseHandler(SseHub hub)
	{
		return ex ->
		{
			if (!ex.getRequestMethod().equals("GET"))
			{
				try (ex)
				{
					sendError(ex, 405, "method not allowed");
				}
				return;
			}
			try
			{
				hub.register(ex);
			}
			catch (Exception e)
			{
				log.debug("SSE register failed: " + e.toString());
				ex.close();
			}
		};
	}

	// --- /api/status --------------------------------------------------------------------

	/**
	 * The site, the version and one entry per configured labeller. The page uses the ids to
	 * build its filter list, so this is what makes "show me just this labeller" possible —
	 * the log lines themselves are matched on the thread name log4j2 prints as {@code [%t]},
	 * which is the labeller id for its own thread and {@code "<id> RX"} / {@code "<id> TX"}
	 * for its comms threads.
	 */
	private void handleStatus(HttpExchange ex) throws Exception
	{
		if (!ex.getRequestMethod().equals("GET"))
		{
			sendError(ex, 405, "method not allowed");
			return;
		}

		Json.Arr arr = Json.arr();
		for (Labeller labeller : server.getLabellers())
		{
			LabellerProperties prop = labeller.getLabellerProperties();
			arr.add(Json.obj()
				.put("id", prop.getId())
				.put("ipAddress", prop.getIpAddress())
				.put("portNumber", prop.getPortNumber())
				.put("running", labeller.isAlive())
				.put("connected", labeller.isConnected()));
		}

		sendJson(ex, 200, Json.obj()
			.put("appName", "LabelServer4j")
			.put("appVersion", Labeller.version)
			.put("site", Server.Site)
			.put("serverTime", System.currentTimeMillis())
			.putRaw("labellers", arr.build())
			.build());
	}

	// --- /api/log/tail ------------------------------------------------------------------

	/** Returns the backlog of recent log lines; the page then follows via SSE. */
	private void handleLogTail(HttpExchange ex) throws Exception
	{
		if (!ex.getRequestMethod().equals("GET"))
		{
			sendError(ex, 405, "method not allowed");
			return;
		}
		int lines = LOG_TAIL_DEFAULT;
		String query = ex.getRequestURI().getQuery();
		if (query != null && query.startsWith("lines="))
		{
			try
			{
				lines = Math.max(1, Math.min(5000, Integer.parseInt(query.substring("lines=".length()))));
			}
			catch (NumberFormatException ignored)
			{
				// keep the default
			}
		}
		Json.Arr arr = Json.arr();
		for (String line : logTailer.tail(lines))
		{
			arr.add(line);
		}
		sendJson(ex, 200, Json.obj().putRaw("lines", arr.build()).build());
	}

	// --- static files -------------------------------------------------------------------

	private void handleStatic(HttpExchange ex) throws Exception
	{
		if (!ex.getRequestMethod().equals("GET"))
		{
			sendError(ex, 405, "method not allowed");
			return;
		}
		String path = ex.getRequestURI().getPath();
		String rel = path.equals("/") ? "index.html" : path.substring(1);
		Path target = WEB_ROOT.resolve(rel).normalize();
		if (!target.startsWith(WEB_ROOT))
		{
			sendError(ex, 403, "forbidden");
			return;
		}
		if (!Files.isRegularFile(target))
		{
			sendError(ex, 404, "not found");
			return;
		}
		byte[] body = Files.readAllBytes(target);
		ex.getResponseHeaders().set("Content-Type", contentType(target));
		// Revalidate on every use so a browser never serves a stale page after an upgrade.
		ex.getResponseHeaders().set("Cache-Control", "no-cache");
		ex.sendResponseHeaders(200, body.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(body);
		}
	}

	private static String contentType(Path file)
	{
		String n = file.getFileName().toString().toLowerCase();
		if (n.endsWith(".html"))
		{
			return "text/html; charset=utf-8";
		}
		if (n.endsWith(".css"))
		{
			return "text/css; charset=utf-8";
		}
		if (n.endsWith(".js"))
		{
			return "application/javascript; charset=utf-8";
		}
		if (n.endsWith(".json"))
		{
			return "application/json; charset=utf-8";
		}
		return "application/octet-stream";
	}

	// --- helpers ------------------------------------------------------------------------

	private void sendJson(HttpExchange ex, int code, String json) throws IOException
	{
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(code, body.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(body);
		}
	}

	private void sendError(HttpExchange ex, int code, String message) throws IOException
	{
		sendJson(ex, code, Json.obj().put("error", message).build());
	}

	private void trySendError(HttpExchange ex, int code, String message)
	{
		try
		{
			sendError(ex, code, message);
		}
		catch (IOException ignored)
		{
			// response already started or socket gone; nothing to do
		}
	}

	private static ThreadFactory daemonFactory()
	{
		AtomicInteger n = new AtomicInteger(1);
		return r ->
		{
			Thread t = new Thread(r, "webserver-http-" + n.getAndIncrement());
			t.setDaemon(true);
			return t;
		};
	}
}
