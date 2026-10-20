package devple.gow.engine.mod;

import devple.gow.engine.combat.EnemyFighter;
import devple.gow.tools.content.Content;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 플러그인 적재·등록표 (49 문서 §13). {@link #start} 를 부르기 전 (테스트·도구) 에는 아무것도 없어 원작 동작 그대로다.
 * <pre>
 *   찾는 곳: 클래스패스 META-INF/services/devple.gow.engine.mod.GowPlugin
 *            + 각 계층의 plugins/*.jar (URLClassLoader, 같은 클래스 이름은 한 번)
 * </pre>
 */
public final class Plugins {

	private static final List<GowPlugin> active = new CopyOnWriteArrayList<>();
	private static final Map<String, Function<EnemyFighter, EnemyController>> enemies = new ConcurrentHashMap<>();
	private static volatile boolean started;

	private Plugins() {
	}

	/** 플러그인을 찾아 onStart 를 부른다 (두 번째부터는 아무것도 안 함). */
	public static synchronized void start() {
		if (started) {
			return;
		}
		started = true;
		List<URL> jars = new ArrayList<>();
		for (Path p : Content.listAll("plugins")) {
			if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
				try {
					jars.add(p.toUri().toURL());
				} catch (MalformedURLException e) {
					System.err.println("[plugins] " + p + ": " + e.getMessage());
				}
			}
		}
		ClassLoader parent = Plugins.class.getClassLoader();
		ClassLoader cl = jars.isEmpty() ? parent : new URLClassLoader(jars.toArray(URL[]::new), parent);
		Set<String> seen = new HashSet<>();
		PluginContext ctx = new PluginContext();
		var it = ServiceLoader.load(GowPlugin.class, cl).iterator();
		while (true) {
			GowPlugin p;
			try {
				if (!it.hasNext()) {
					break;
				}
				p = it.next();
			} catch (ServiceConfigurationError e) {
				System.err.println("[plugins] 적재 실패: " + e.getMessage());
				continue;
			}
			if (!seen.add(p.getClass().getName())) {
				continue;
			}
			try {
				p.onStart(ctx);
				active.add(p);
				System.out.println("[plugins] " + p.name());
			} catch (RuntimeException e) {
				System.err.println("[plugins] " + p.name() + " onStart 실패: " + e);
			}
		}
	}

	/** 등록표·플러그인을 비운다 (테스트). */
	public static synchronized void reset() {
		active.clear();
		enemies.clear();
		started = false;
	}

	public static List<GowPlugin> active() {
		return List.copyOf(active);
	}

	/** 매 프레임 훅 (GameScene). 예외를 낸 플러그인은 끈다. */
	public static void frame(float dt) {
		for (GowPlugin p : active) {
			try {
				p.onFrame(dt);
			} catch (RuntimeException e) {
				System.err.println("[plugins] " + p.name() + " onFrame 실패 — 끔: " + e);
				active.remove(p);
			}
		}
	}

	static void register(String key, Function<EnemyFighter, EnemyController> factory) {
		enemies.put(key.trim().toLowerCase(Locale.ROOT), factory);
	}

	/** 적 1체의 대체 제어: keys (낮음 → 높음) 중 가장 구체적인 등록. 없으면 null (원래 AI). */
	public static EnemyController controllerFor(EnemyFighter f, List<String> keys) {
		if (enemies.isEmpty()) {
			return null;
		}
		for (int i = keys.size() - 1; i >= 0; i--) {
			Function<EnemyFighter, EnemyController> fac = enemies.get(keys.get(i));
			if (fac != null) {
				EnemyController c = fac.apply(f);
				if (c != null) {
					return c;
				}
			}
		}
		return null;
	}
}
