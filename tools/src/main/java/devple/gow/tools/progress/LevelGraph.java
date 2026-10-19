package devple.gow.tools.progress;

import devple.gow.tools.script.EscOp;
import devple.gow.tools.script.EscScript;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 레벨 ESC 스크립트에서 씬 이동 관련 GameMgr 메시지 호출(Internal 스코프 함수 = GameMgr_OnMessage 0x1B01E8 메시지 ID)과
 * 그 상수 인자를 모아 WAD 간 연결 그래프를 만든다.
 */
public final class LevelGraph {

	/** GameMgr 메시지 ID → 이름 (06/07 문서 + 이번 분석). */
	public static final Map<Integer, String> MESSAGES = Map.ofEntries(
			Map.entry(0x00, "CheckPoint"), Map.entry(0x01, "GetPlayTime"), Map.entry(0x02, "Load"),
			Map.entry(0x03, "LoadWad"), Map.entry(0x04, "LoadForWarp"), Map.entry(0x05, "Warp"),
			Map.entry(0x06, "LoadCheck"), Map.entry(0x07, "Goto"), Map.entry(0x0C, "Idle"),
			Map.entry(0x10, "ZoneTitle?"), Map.entry(0x13, "FailedScreen"),
			Map.entry(0x1F, "ShowItemCard(msg,sec,kind)"), Map.entry(0x2F, "CompleteGame(kind)"),
			Map.entry(0x3A, "SetEndGame"));

	/** 기록할 메시지. */
	private static final TreeSet<Integer> FLOW = new TreeSet<>(List.of(0x00, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x10, 0x1F, 0x2F, 0x3A));

	public record Call(String wad, String script, String entity, int msg, List<String> args) {
	}

	public final List<Call> calls = new ArrayList<>();

	public static LevelGraph scan(Path romDir) throws IOException {
		LevelGraph g = new LevelGraph();
		List<Path> wads;
		try (Stream<Path> s = Files.list(romDir)) {
			wads = s.filter(p -> p.getFileName().toString().endsWith(".WAD")).sorted().toList();
		}
		for (Path p : wads) {
			WadFile wad = WadFile.load(p);
			String name = p.getFileName().toString().replace(".WAD", "");
			for (WadTag t : wad.resources()) {
				if (t.magic() != EscScript.MAGIC) {
					continue;
				}
				EscScript s;
				try {
					s = new EscScript(t.data());
				} catch (RuntimeException e) {
					continue;
				}
				for (EscScript.Entity e : s.entities) {
					for (EscScript.Handler h : e.handlers()) {
						g.collect(name, t.name(), e.name(), h.code());
					}
				}
			}
		}
		return g;
	}

	private void collect(String wad, String script, String entity, List<EscOp> code) {
		List<String> pending = new ArrayList<>();
		for (EscOp op : code) {
			String txt = op.text();
			if (txt.startsWith("push_")) {
				pending.add(txt.substring(txt.indexOf(' ') + 1));
			} else if (txt.startsWith("call_")) {
				String ref = txt.substring(txt.indexOf(' ') + 1);
				if (ref.startsWith("Internal:0x")) {
					int id = Integer.parseInt(ref.substring(11).split(" ")[0], 16);
					if (FLOW.contains(id)) {
						calls.add(new Call(wad, script, entity, id, List.copyOf(pending)));
					}
				}
				pending.clear();
			} else if (!txt.startsWith("int2float") && !txt.startsWith("neg")) {
				pending.clear();
			}
		}
	}

	/** WAD → 이동 대상 WAD 집합 (Load/LoadWad/LoadForWarp/LoadCheck 의 첫 문자열 인자). */
	public Map<String, TreeSet<String>> edges() {
		Map<String, TreeSet<String>> m = new TreeMap<>();
		for (Call c : calls) {
			if ((c.msg >= 0x02 && c.msg <= 0x04) || c.msg == 0x06) {
				c.args.stream().filter(a -> a.startsWith("\"")).findFirst()
						.ifPresent(a -> m.computeIfAbsent(c.wad, k -> new TreeSet<>()).add(a.replace("\"", "").toUpperCase()));
			}
		}
		return m;
	}
}
