package devple.gow.engine.level;

import devple.gow.tools.level.LevelData;
import devple.gow.tools.script.EscOp;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 바닥을 이웃 레벨에 두는 "덧붙는" 레벨의 숙주 레벨 찾기 (37 문서 §8.1).
 * <p>
 * 예: ATHN02D (메두사 여왕 방)는 연출·보스·카메라만 있고 충돌 시트는 마법 획득 단상(면 72, z 1792..1957)뿐이다. 방 바닥·벽은 ATHN02C 의
 * RIB_sheet (z 1248..2832) 에 있고, ATHN02C 의 스크립트가 {@code LoadWad("Athn02D")} / {@code LoadCheck("Athn02D")} 로 이 레벨을
 * 다른 슬롯에 올린다. 원작은 두 슬롯 레벨이 함께 상주하므로 ATHN02D 에 있는 동안에도 ATHN02C 바닥을 밟는다.
 * 규칙 [확실: 데이터]: 이 레벨 스크립트에 레벨 적재 호출(LoadWad/LoadCheck/LoadForWarp/Load)이 하나도 없고 ({@link #addOn}),
 * 같은 접두사(앞 6자) 레벨 중 스크립트가 이 레벨 이름으로 LoadWad/LoadCheck 를 부르며 그 충돌 시트가 주어진 점 아래에 바닥을 가진 레벨.
 * (보통 레벨은 이웃으로 돌아가는 LoadWad/LoadCheck 센서를 스스로 가진다 — 그런 레벨의 이웃 바닥은 WALK 전환 대상이지 숙주가 아니다.)
 */
public final class HostLevel {

	private HostLevel() {
	}

	/** 숙주 레벨 이름 (WAD 확장자 없이 대문자), 없으면 null. (x, y, z) = 이 레벨에서 서 있어야 할 점 (시작 위치 등). */
	public static String find(Path rom, String level, float x, float y, float z) {
		return find(candidates(rom, level), x, y, z);
	}

	/** 숙주 후보: 이 레벨을 적재하는 같은 접두사 레벨 (이름 순) 과 그 충돌. */
	public record Candidate(String name, LevelCollision collision) {
	}

	/**
	 * {@link #find} 의 무거운 앞부분 (WAD 읽기·스크립트 검사·충돌 만들기). GL 없음 → 적재 스레드에서 미리 해도 된다 (48 문서 §9). 목록을 못 읽으면 null.
	 */
	public static List<Candidate> candidates(Path rom, String level) {
		String me = level.toUpperCase(Locale.ROOT);
		String prefix = me.substring(0, Math.min(6, me.length()));
		List<Path> cands;
		try (Stream<Path> s = Files.list(rom)) {
			cands = s.filter(p -> {
				String n = p.getFileName().toString().toUpperCase(Locale.ROOT);
				return n.endsWith(".WAD") && n.startsWith(prefix) && !n.equals(me + ".WAD");
			}).sorted().toList();
		} catch (IOException e) {
			return null;
		}
		List<Candidate> out = new java.util.ArrayList<>();
		for (Path p : cands) {
			try {
				WadFile w = WadFile.load(p);
				if (!loads(w, me)) {
					continue;
				}
				String n = p.getFileName().toString();
				out.add(new Candidate(n.substring(0, n.length() - 4).toUpperCase(Locale.ROOT), new LevelCollision(new LevelData(w).sheets)));
			} catch (IOException | RuntimeException ignored) {
				// 읽지 못하는 WAD 는 건너뜀
			}
		}
		return out;
	}

	/** 후보 중 (x, y, z) 아래에 바닥이 있는 첫 레벨 (없으면 null). */
	public static String find(List<Candidate> cands, float x, float y, float z) {
		if (cands == null) {
			return null;
		}
		for (Candidate c : cands) {
			try {
				if (!Float.isNaN(c.collision().groundHeight(x, y + 32, z, 600))) {
					return c.name();
				}
			} catch (RuntimeException ignored) {
				// 예전과 같이 건너뜀
			}
		}
		return null;
	}

	/** 레벨 스크립트에 레벨 적재 호출(Internal LoadWad/LoadCheck/LoadForWarp/Load)이 하나도 없는가 (ATHN02D). */
	public static boolean addOn(WadFile wad) {
		for (WadTag t : wad.resources()) {
			if (t.magic() != EscScript.MAGIC || !t.name().startsWith("ESC_")) {
				continue;
			}
			for (EscScript.Entity e : new EscScript(t.data()).entities) {
				for (EscScript.Handler h : e.handlers()) {
					for (EscOp op : h.code()) {
						String x = op.text();
						if (x.startsWith("call") && (x.endsWith(" LoadWad") || x.endsWith(" LoadCheck") || x.endsWith(" LoadForWarp") || x.endsWith(" Load"))) {
							return false;
						}
					}
				}
			}
		}
		return true;
	}

	/** 레벨 스크립트에 {@code push_string "<level>"} 바로 뒤 Internal LoadWad(0x3) / LoadCheck(0x6) 호출이 있는가. */
	public static boolean loads(WadFile wad, String level) {
		String lit = "push_string \"" + level.toLowerCase(Locale.ROOT) + "\"";
		for (WadTag t : wad.resources()) {
			if (t.magic() != EscScript.MAGIC || !t.name().startsWith("ESC_")) {
				continue;
			}
			EscScript s = new EscScript(t.data());
			for (EscScript.Entity e : s.entities) {
				for (EscScript.Handler h : e.handlers()) {
					List<EscOp> c = h.code();
					for (int i = 0; i + 1 < c.size(); i++) {
						String next = c.get(i + 1).text();
						if (c.get(i).text().toLowerCase(Locale.ROOT).equals(lit)
								&& (next.endsWith("LoadWad") || next.endsWith("LoadCheck"))) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}
}
