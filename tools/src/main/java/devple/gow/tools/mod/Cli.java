package devple.gow.tools.mod;

import devple.gow.tools.content.GameConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 모드 명령 (49 문서 §15).
 * <pre>
 *   mod:new mymod        mods/mymod/ 뼈대 (README.md, rom/, wad/, plugins/, ai.properties 예시) — 이미 있는 파일은 건드리지 않음
 * </pre>
 * 모드 디렉터리는 gow.properties 의 modsDir (기본 mods).
 */
public final class Cli {

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "new" -> {
				Path d = create(GameConfig.load().modsDir(), args[1]);
				System.out.println(d + " 만듦. 켜기: gow.properties 에 mods=" + args[1]);
			}
			default -> throw new IllegalArgumentException("mod:<new> " + args[0]);
		}
	}

	/** 모드 뼈대. @return 모드 디렉터리 */
	public static Path create(Path modsDir, String name) throws IOException {
		if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains(",") || name.startsWith(".")) {
			throw new IllegalArgumentException("모드 이름: 영문·숫자 (쉼표·경로 문자 없이) — " + name);
		}
		Path d = modsDir.resolve(name);
		for (String sub : new String[]{"rom", "wad", "plugins"}) {
			Files.createDirectories(d.resolve(sub));
		}
		write(d.resolve("README.md"), readme(name));
		write(d.resolve("ai.properties"), """
				# 적 AI 수치 덮어쓰기 (docs/49 §13). <열쇠>.<항목>=값, 값 앞 * 는 배율
				# 열쇠: * (모두) < WAD 이름 (r_min, min) < 템플릿 Instance Name < AI1 번호 (50, 050)
				#min.health=200
				#050.motion.ground=*1.5
				#*.damage=*2
				""");
		return d;
	}

	private static void write(Path f, String s) throws IOException {
		if (!Files.exists(f)) {
			Files.writeString(f, s);
		}
	}

	private static String readme(String name) {
		return """
				# %1$s

				God of War (devple-gow) 모드. 설명은 저장소의 docs/49-extensibility.md.

				켜기: 저장소 루트의 `gow.properties` 에 `mods=%1$s` (여럿이면 쉼표, 뒤가 우선).

				| 자리 | 하는 일 |
				|------|---------|
				| `rom/<이름>` | rom 파일 통째 교체 (WAD·VAG·VPK·PSS) — §3 |
				| `wad/<WAD>/<태그>.bin` | WAD 안 리소스 하나 교체. 꺼내기: `tools wad:get gow-rom/<WAD>.WAD <태그> out.bin` — §5 |
				| `ai.properties` | 적 AI 수치 덮어쓰기 — §13 |
				| `plugins/*.jar` | 코드 플러그인 (`devple.gow.engine.mod.GowPlugin`, META-INF/services) — §13 |
				| `*.properties` | 종류별 표 (계층마다 키 단위로 덮어 합침) — §6–§14 |

				원본 디스크에서 나온 파일은 배포하지 마세요.
				""".formatted(name);
	}
}
