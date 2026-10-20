package devple.gow.tools.iso;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PS2 디스크 루트의 SYSTEM.CNF (49 문서 §15). 한글판:
 * <pre>
 *   BOOT2 = cdrom0:\SCKA_300.02;1
 *   VER = 1.01
 *   VMODE = NTSC
 * </pre>
 * 부트 실행 파일 이름의 앞 네 글자 (SCKA·SCUS·SCES …) 로 판의 지역을 짐작한다.
 *
 * @param values 키 → 값 (대문자 키, 앞뒤 공백 제거)
 */
public record SystemCnf(Map<String, String> values) {

	/** ISO 루트의 SYSTEM.CNF (없으면 null). */
	public static SystemCnf read(IsoImage iso) throws IOException {
		for (IsoEntry e : iso.listAll()) {
			if (!e.dir() && e.path().equalsIgnoreCase("/SYSTEM.CNF")) {
				return parse(StandardCharsets.ISO_8859_1.decode(iso.read(e, 0, (int) e.size())).toString());
			}
		}
		return null;
	}

	public static SystemCnf parse(String text) {
		Map<String, String> m = new LinkedHashMap<>();
		for (String line : text.split("\\r?\\n|\\r")) {
			int eq = line.indexOf('=');
			if (eq > 0) {
				m.put(line.substring(0, eq).trim().toUpperCase(Locale.ROOT), line.substring(eq + 1).trim());
			}
		}
		return new SystemCnf(Map.copyOf(m));
	}

	/** BOOT2 (PS2) 또는 BOOT (PS1) 원문. 없으면 null. */
	public String boot() {
		String b = values.get("BOOT2");
		return b != null ? b : values.get("BOOT");
	}

	/** 부트 실행 파일 이름 (cdrom0:\SCKA_300.02;1 → SCKA_300.02). 없으면 null. */
	public String executable() {
		String b = boot();
		if (b == null) {
			return null;
		}
		int c = b.indexOf(':');
		String s = c >= 0 ? b.substring(c + 1) : b;
		s = s.replace('\\', '/');
		s = s.substring(s.lastIndexOf('/') + 1);
		int semi = s.indexOf(';');
		return (semi >= 0 ? s.substring(0, semi) : s).trim();
	}

	/** 제품 번호 (SCKA_300.02 → SCKA-30002). */
	public String serial() {
		String e = executable();
		return e == null ? null : e.replace("_", "-").replace(".", "");
	}

	public String version() {
		return values.get("VER");
	}

	public String videoMode() {
		return values.get("VMODE");
	}

	/** 제품 번호 앞 네 글자로 짐작한 지역 (SCKA → 한국). 모르면 "?". */
	public String region() {
		String e = executable();
		if (e == null || e.length() < 4) {
			return "?";
		}
		return switch (e.substring(0, 4).toUpperCase(Locale.ROOT)) {
			case "SCUS", "SLUS", "PBPX" -> "북미 (NTSC-U)";
			case "SCES", "SLES", "SCED" -> "유럽 (PAL)";
			case "SCPS", "SLPS", "SLPM", "SCAJ" -> "일본·아시아 (NTSC-J)";
			case "SCKA", "SLKA" -> "한국 (NTSC-K)";
			default -> "?";
		};
	}
}
