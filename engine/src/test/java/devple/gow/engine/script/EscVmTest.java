package devple.gow.engine.script;

import devple.gow.tools.script.EscScript;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EscVmTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	/** 호출 기록용 호스트. ShowMessage(Internal:0xa) 는 (msgId:int, sec:float) 2개를 pop. */
	static final class RecordingHost implements EscHost {
		final List<String> calls = new ArrayList<>();

		@Override
		public Object call(EscContext ctx, int scope, int func, int rt, Deque<Object> st) {
			if (scope == 1 && func == 0xa) {
				float sec = EscVm.f(st.pop());
				int msg = EscVm.i(st.pop());
				calls.add("ShowMessage(" + msg + "," + sec + ")");
			}
			return null;
		}

		@Override
		public Object getVar(EscContext ctx, int scope, int id, int type) {
			return type == 3 ? "" : 0;
		}

		@Override
		public void setVar(EscContext ctx, int scope, int id, int type, Object v) {
		}
	}

	private static List<EscScript> scripts(String wad) throws Exception {
		Path p = ROM.resolve(wad);
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		List<EscScript> out = new ArrayList<>();
		for (WadTag t : WadFile.load(p).resources()) {
			if (t.magic() == EscScript.MAGIC) {
				out.add(new EscScript(t.data()));
			}
		}
		return out;
	}

	@Test
	void startEntityParameterIsHero() throws Exception {
		EscVm vm = new EscVm(new RecordingHost());
		for (EscScript s : scripts("ARENA06.WAD")) {
			for (EscScript.Entity e : s.entities) {
				if (e.typeName().equals("Start")) {
					Object v = vm.run(new EscContext("ESC", e), e.handlers().getFirst());
					assertEquals("Hero", v);
					return;
				}
			}
		}
		fail("Start 엔티티 없음");
	}

	@Test
	void tutorialShowsMessage269() throws Exception {
		RecordingHost host = new RecordingHost();
		EscVm vm = new EscVm(host);
		for (EscScript s : scripts("ATHN01A.WAD")) {
			for (EscScript.Entity e : s.entities) {
				for (EscScript.Handler h : e.handlers()) {
					if (h.code().stream().anyMatch(op -> op.text().contains("ShowMessage"))) {
						vm.run(new EscContext("ESC", e), h);
					}
				}
			}
		}
		assertTrue(host.calls.contains("ShowMessage(269,5.0)"), host.calls.toString());
	}

	/** 모든 레벨의 모든 핸들러가 예외 없이 끝까지 실행되는지 (호스트는 더미). */
	@Test
	void allHandlersRun() throws Exception {
		Assumptions.assumeTrue(Files.isDirectory(ROM), "gow-rom 없음");
		EscVm vm = new EscVm(new RecordingHost());
		int n = 0;
		try (var files = Files.list(ROM)) {
			for (Path p : files.filter(f -> f.toString().endsWith(".WAD")).toList()) {
				for (WadTag t : WadFile.load(p).resources()) {
					if (t.magic() != EscScript.MAGIC) {
						continue;
					}
					EscScript s = new EscScript(t.data());
					for (EscScript.Entity e : s.entities) {
						for (EscScript.Handler h : e.handlers()) {
							vm.run(new EscContext(p.getFileName() + ":" + t.name(), e), h);
							n++;
						}
					}
				}
			}
		}
		assertTrue(n > 10000, "핸들러 수 " + n);
	}
}
