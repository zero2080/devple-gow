package devple.gow.tools.content;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 사운드 WAV 교체 (49 문서 §11). 계층의 {@code sound/<SND이름>.wav} 가 그 이름의 소리 (효과음 SND_*, 음악 SND_M_*) 대신 재생된다.
 * 반복 여부: WAV smpl 루프 → {@code sound.properties} 의 {@code loop.<SND이름>=true|false} → 음악은 반복, 효과음은 한 번.
 * 이름은 대소문자를 가리지 않는다.
 */
public final class SoundOverrides {

	public static final String DIR = "sound";
	public static final String FILE = "sound.properties";

	private record Table(Map<String, Path> files, Properties props) {
	}

	private static final LayerCache<Table> TABLE = new LayerCache<>(c -> scan());

	private SoundOverrides() {
	}

	private static Table scan() {
		Map<String, Path> m = new HashMap<>();
		for (Path f : Content.listAll(DIR)) { // 낮음 → 높음: 위 계층이 덮어씀
			String n = f.getFileName().toString();
			if (n.toLowerCase(Locale.ROOT).endsWith(".wav")) {
				m.put(n.substring(0, n.length() - 4).toUpperCase(Locale.ROOT), f);
			}
		}
		Properties p = m.isEmpty() ? new Properties() : Content.properties(FILE);
		return new Table(Map.copyOf(m), p);
	}

	/** 교체 WAV (없으면 null). */
	public static Path find(String sndName) {
		Map<String, Path> m = TABLE.get().files;
		return m.isEmpty() || sndName == null ? null : m.get(sndName.toUpperCase(Locale.ROOT));
	}

	/** sound.properties 의 loop.&lt;이름&gt; (없으면 def). */
	public static boolean loop(String sndName, boolean def) {
		Properties p = TABLE.get().props;
		String v = p.getProperty("loop." + sndName);
		if (v == null) {
			for (String k : p.stringPropertyNames()) {
				if (k.equalsIgnoreCase("loop." + sndName)) {
					v = p.getProperty(k);
				}
			}
		}
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}
}
