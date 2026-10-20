package devple.gow.tools.content;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * 실행 설정 (49 문서 §2). 우선순위: 시스템 속성 {@code -Dgow.<키>} → 작업 디렉터리의 {@code gow.properties} → 기본값.
 * <pre>
 *   rom    = gow-rom          추출된 디스크 파일 디렉터리
 *   iso    = P2_gow_kor.iso   동영상을 ISO 에서 바로 읽을 때 (없으면 rom 부모에서 *.iso 탐색)
 *   region = K                언어 접미사 (R_PERM<접미사>.WAD). 비우면 rom 에서 자동 감지
 *   mods   = mymod,other      켤 모드 (mods/ 아래 디렉터리 이름, 뒤에 적을수록 우선)
 *   modsDir = mods
 * </pre>
 */
public final class GameConfig {

	public static final String FILE = "gow.properties";

	private final Properties props;

	public GameConfig(Properties props) {
		this.props = props;
	}

	/** 작업 디렉터리의 gow.properties (없으면 빈 설정). */
	public static GameConfig load() {
		return load(Path.of(FILE));
	}

	public static GameConfig load(Path file) {
		Properties p = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
			} catch (IOException e) {
				System.err.println("[config] " + file + " 읽기 실패: " + e.getMessage());
			}
		}
		return new GameConfig(p);
	}

	/** -Dgow.KEY → 파일의 KEY → def. */
	public String get(String key, String def) {
		String s = System.getProperty("gow." + key);
		if (s != null) {
			return s;
		}
		return props.getProperty(key, def);
	}

	public Path romDir() {
		return Path.of(get("rom", "gow-rom"));
	}

	/** 명시된 ISO 경로 (없으면 null — 탐색은 {@link Content#isoPath()}). */
	public Path iso() {
		String s = get("iso", null);
		return s == null || s.isBlank() ? null : Path.of(s);
	}

	public String region() {
		String s = get("region", null);
		return s == null || s.isBlank() ? null : s.trim();
	}

	public Path modsDir() {
		return Path.of(get("modsDir", "mods"));
	}

	/** 켤 모드 이름 (낮은 우선 → 높은 우선). */
	public List<String> mods() {
		String s = get("mods", "");
		List<String> out = new ArrayList<>();
		Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).forEach(out::add);
		return out;
	}
}
