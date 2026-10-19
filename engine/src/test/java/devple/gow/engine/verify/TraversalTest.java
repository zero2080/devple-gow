package devple.gow.engine.verify;

import devple.gow.engine.verify.TraversalVerifier.LevelResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 전 레벨 순회 검증 (36 문서). 결과 보고서는 gow-rom 실제 경로 옆 work/verify/report.md 에 쓴다 (디렉터리가 있을 때).
 */
class TraversalTest {

	private static TraversalVerifier v;

	@BeforeAll
	static void run() throws IOException {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_PERMK.WAD")), "gow-rom 없음");
		v = new TraversalVerifier(rom).runAll();
		Path out = rom.toRealPath().getParent().resolve("work/verify");
		if (Files.isDirectory(out)) {
			Files.writeString(out.resolve("report.md"), TraversalReport.markdown(v));
		}
	}

	@Test
	void allLevelsRan() {
		assertTrue(v.results.size() > 100, "레벨 " + v.results.size());
	}

	@Test
	void noScriptExceptionsOrUnbalancedStacks() {
		List<String> bad = v.results.values().stream().filter(r -> !r.exceptions.isEmpty() || !r.unbalanced.isEmpty())
				.map(r -> r.level + " " + r.exceptions + " " + r.unbalanced).toList();
		assertTrue(bad.isEmpty(), bad.toString());
	}

	@Test
	void transitionTargetsExist() {
		List<String> bad = v.results.values().stream().flatMap(r -> r.edges.stream().filter(e -> !e.targetExists())
				.map(e -> r.level + "→" + e.target())).toList();
		assertTrue(bad.isEmpty(), bad.toString());
	}

	@Test
	void moviesResolve() {
		List<String> bad = v.results.values().stream().flatMap(r -> r.movies.entrySet().stream().filter(e -> !e.getValue())
				.map(e -> r.level + ":" + e.getKey())).toList();
		assertTrue(bad.isEmpty(), bad.toString());
		assertTrue(v.results.values().stream().mapToInt(r -> r.movies.size()).sum() > 20);
	}

	@Test
	void messagesResolve() {
		List<String> bad = v.results.values().stream().flatMap(r -> r.messages.entrySet().stream().filter(e -> !e.getValue())
				.map(e -> r.level + ":" + e.getKey())).toList();
		assertTrue(bad.isEmpty(), bad.toString());
	}

	@Test
	void olympusReachableFromStart() {
		List<String> path = v.path(TraversalVerifier.START, TraversalVerifier.END);
		assertFalse(path.isEmpty(), "도달: " + v.reach(TraversalVerifier.START).keySet());
	}

	@Test
	void bonusArenaChain() {
		List<String> path = v.path(TraversalVerifier.BONUS_START, TraversalVerifier.BONUS_END);
		assertEquals(List.of("ARENA10", "ARENA08", "ARENA06", "ARENA09", "ARENA12", "ARENA14", "ARENA26", "ARENA20", "ARENA19", "ARENA27"), path);
	}

	/** 새 게임 첫 구간은 게임 이벤트 강제 없이 걷기만으로 이어진다 (LoadCheck 이음). */
	@Test
	void openingChainByWalking() {
		Map<String, String> walk = v.reach(TraversalVerifier.START, false);
		assertTrue(walk.keySet().containsAll(List.of("ATHN01B", "ATHN01C", "ATHN01D")), walk.keySet().toString());
	}

	@Test
	void report() {
		String md = TraversalReport.markdown(v);
		assertTrue(md.contains("| ATHN01A |"));
		Map<String, LevelResult> r = v.results;
		assertTrue(r.get("ATHN01A").spawnGround);
	}
}
