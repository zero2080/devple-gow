package devple.gow.tools.content;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 콘텐츠 계층 (49 문서 §3). 원본 추출물(rom) 위에 모드 디렉터리들을 겹쳐, 같은 상대 경로가 있으면 위 계층 것을 쓴다.
 * <pre>
 *   계층 (낮음 → 높음): rom  &lt;  mods/&lt;mods[0]&gt;  &lt;  mods/&lt;mods[1]&gt; ...  &lt;  config/
 *   계층 디렉터리 안:
 *     rom/NAME              rom 파일 통째 교체 (WAD·VAG·VPK·PSS ...)
 *     wad/WAD이름/태그.bin   WAD 태그 데이터 교체 ({@link WadPatches})
 *     *.properties          계층마다 덮어 합치는 표 ({@link #properties})
 * </pre>
 * 모드가 없으면 모든 조회가 원래 경로를 그대로 돌려준다 (원작 동작 그대로).
 */
public final class Content {

	/** 모드와 무관하게 늘 맨 위에 있는 사용자 계층 (키 설정 등 개인 설정). */
	public static final String USER_LAYER = "config";

	private static volatile Content current;

	private final GameConfig config;
	private final List<Path> layers; // 낮음 → 높음

	private Content(GameConfig config, List<Path> layers) {
		this.config = config;
		this.layers = List.copyOf(layers);
	}

	/** 설정에서 계층을 만든다: 켠 모드 (없는 디렉터리는 경고 후 건너뜀) + 사용자 계층. */
	public static Content of(GameConfig config) {
		List<Path> ls = new ArrayList<>();
		for (String m : config.mods()) {
			Path d = config.modsDir().resolve(m);
			if (Files.isDirectory(d)) {
				ls.add(d);
			} else {
				System.err.println("[mods] " + d + " 없음 — 건너뜀");
			}
		}
		Path user = Path.of(config.get("userDir", USER_LAYER));
		if (Files.isDirectory(user)) {
			ls.add(user);
		}
		return new Content(config, ls);
	}

	/** 지정 계층 (테스트·도구용). */
	public static Content of(GameConfig config, List<Path> layers) {
		return new Content(config, layers);
	}

	public static Content get() {
		Content c = current;
		if (c == null) {
			synchronized (Content.class) {
				if (current == null) {
					current = of(GameConfig.load());
					if (!current.layers.isEmpty()) {
						System.out.println("[mods] 계층: " + current.layers);
					}
				}
				c = current;
			}
		}
		return c;
	}

	/** 전역 계층을 바꾼다 (null = 다음 조회 때 설정에서 다시 만듦). */
	public static void install(Content c) {
		current = c;
		WadPatches.clearCache();
		Region.clearCache();
	}

	public GameConfig config() {
		return config;
	}

	public List<Path> layers() {
		return layers;
	}

	/** 설정의 rom 디렉터리. */
	public static Path romDir() {
		return get().config.romDir();
	}

	/**
	 * rom 파일 경로: 위 계층의 rom/NAME 이 있으면 그것, 없으면 base/NAME. 엔진의 모든 rom 파일 접근이 이 함수를 지난다.
	 */
	public static Path file(Path base, String name) {
		return get().resolve(base, name);
	}

	public Path resolve(Path base, String name) {
		for (int i = layers.size() - 1; i >= 0; i--) {
			Path p = layers.get(i).resolve("rom").resolve(name);
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return base.resolve(name);
	}

	/**
	 * WAD 옆 파일 (예: 레벨 WAD 옆 R_PERM): wad 디렉터리 기준으로 계층 조회, 없으면 설정 rom 디렉터리 기준
	 * (wad 가 모드의 rom/ 에서 온 경우).
	 */
	public static Path sibling(Path wad, String name) {
		Path p = file(wad.toAbsolutePath().getParent(), name);
		return Files.exists(p) ? p : file(romDir(), name);
	}

	/** 가장 위 계층의 rel 파일. */
	public static Optional<Path> find(String rel) {
		List<Path> all = findAll(rel);
		return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
	}

	/** 모든 계층의 rel 파일 (낮음 → 높음). */
	public static List<Path> findAll(String rel) {
		List<Path> out = new ArrayList<>();
		for (Path l : get().layers) {
			Path p = l.resolve(rel);
			if (Files.exists(p)) {
				out.add(p);
			}
		}
		return out;
	}

	/** 모든 계층의 rel 디렉터리 안 파일 (낮음 → 높음 순, 같은 이름은 위 계층이 뒤에). */
	public static List<Path> listAll(String relDir) {
		List<Path> out = new ArrayList<>();
		for (Path d : findAll(relDir)) {
			if (!Files.isDirectory(d)) {
				continue;
			}
			try (Stream<Path> s = Files.list(d)) {
				s.filter(Files::isRegularFile).sorted().forEach(out::add);
			} catch (IOException e) {
				System.err.println("[mods] " + d + " 목록 실패: " + e.getMessage());
			}
		}
		return Collections.unmodifiableList(out);
	}

	/** 모든 계층의 rel (.properties) 을 아래부터 덮어 합친다. 없으면 빈 표. */
	public static Properties properties(String rel) {
		Properties out = new Properties();
		for (Path p : findAll(rel)) {
			try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
				Properties one = new Properties();
				one.load(r);
				out.putAll(one);
			} catch (IOException e) {
				System.err.println("[mods] " + p + " 읽기 실패: " + e.getMessage());
			}
		}
		return out;
	}

	/**
	 * 동영상용 ISO: 설정 iso → 작업 디렉터리의 *.iso → rom 실제 경로의 부모의 *.iso (워크트리의 rom 심볼릭 링크 대응). 없으면 null.
	 */
	public static Path isoPath(Path rom) {
		Path p = get().config.iso();
		if (p != null) {
			return p;
		}
		Path here = firstIso(Path.of("."));
		if (here != null) {
			return here;
		}
		try {
			Path parent = rom.toRealPath().getParent();
			return parent == null ? null : firstIso(parent);
		} catch (IOException e) {
			return null;
		}
	}

	private static Path firstIso(Path dir) {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".iso") && Files.isRegularFile(f))
					.sorted().findFirst().map(Path::normalize).orElse(null);
		} catch (IOException e) {
			return null;
		}
	}
}
