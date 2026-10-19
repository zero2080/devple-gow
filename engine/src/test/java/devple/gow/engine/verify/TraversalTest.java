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

	/**
	 * 38 문서: 원작 상호작용·줄로 풀린 막힘 (게임 이벤트 강제 없이) — 쌍발차기 문 CSM 과제 206 (1023), 판도라 상자 Marker 511,
	 * 돛대 짚라인 ATHN01D → ATHN01E, 엘리베이터 위 Warp 도착 (ATHN04A → ATHN05D).
	 */
	@Test
	void traversalMechanicsUnblockMainPath() {
		assertTrue(unforced("ATHN01D", "ATHN01F"), "쌍발차기 문 (R1 연타 → 1023 과제 206)");
		assertTrue(unforced("PAND05B", "HDES01A"), "판도라 상자 밀기 (Marker 511)");
		assertTrue(v.results.get("ATHN01D").edges.stream().anyMatch(e -> e.target().equals("ATHN01E") && !e.forced()
				&& e.source().startsWith("줄 gorope3")), "짚라인");
		assertTrue(v.results.get("ATHN05D").arrivals.stream().filter(a -> a.from().equals("ATHN04A")).allMatch(TraversalVerifier.Arrival::ok),
				v.results.get("ATHN05D").arrivals.toString());
		assertTrue(v.results.get("ATHN01D").interactions.stream().anyMatch(s -> s.contains("gotwinkickdoor 과제 206")));
	}

	/**
	 * 남은 주 경로 막힘 6 곳이 원작 게임 동작으로 열린다 (게임 이벤트 강제 없음):
	 * DEST03 (동영상 끝 501 보낸 쪽 = 플레이어 + 세이렌 MaximumCount 3×3 + 두 번째 뿔피리 CSM), PAND00C (엘리베이터 레버 = PushPull 1023 id 111),
	 * PAND04AA (Semo06 끝 501), ATHN08A / ATHN08C (헤드리스 아레스전 → 1047 608 / 1035 395 → 컷신 → Warp), ATHN08B (분신 파도 MaximumCount → A07MP05).
	 */
	@Test
	void mainPathNeedsNoForcedEvents() {
		assertTrue(unforced("DEST03", "PAND00A"), "DEST03 뿔피리·세이렌");
		assertTrue(unforced("PAND00C", "PAND04AA"), "엘리베이터 레버 1023 id 111");
		assertTrue(unforced("PAND04AA", "PAND04C"), "Semo06 → LevelData1 → LoadWad");
		assertTrue(unforced("ATHN08A", "ATHN08B"), "아레스 1단계");
		assertTrue(unforced("ATHN08B", "ATHN08C"), "분신 파도 25");
		assertTrue(unforced("ATHN08C", "ATHN07B"), "아레스 3단계");
		assertTrue(v.reach(TraversalVerifier.START, false).containsKey(TraversalVerifier.END), "걷기·상호작용·전투만으로 OLYMP02");
	}

	/**
	 * 되돌아가는 전환도 강제 없이 (42 문서): ATHN01E 선장 = 버튼 1024 → a01MP04 → LoadWad(Athn01D) 뒤 짚라인, PAND01C 손잡이 질문
	 * (Internal 0xB 1019 → 1021 예), PAND02AA 회전방 (레버 60 → Animator 51.7 초 → 벽 관절 Marker 202 → spinRmEnd → 레버 → LoadWad(Pand02A)).
	 * 강제 이벤트 뒤에만 나오는 전환이 하나도 없다.
	 */
	@Test
	void backtrackingNeedsNoForcedEvents() {
		assertTrue(unforced("ATHN01E", "ATHN01D"), "선장 1024");
		assertTrue(unforced("PAND01C", "PAND01B"), "손잡이 질문 1021");
		assertTrue(unforced("PAND02AA", "PAND02A"), "회전방 Animator → Marker 202");
		List<String> forced = v.results.values().stream().flatMap(r -> r.edges.stream().filter(e -> e.targetExists() && e.forced())
				.map(e -> r.level + "→" + e.target())).toList();
		assertTrue(forced.isEmpty(), "강제 뒤에만 나온 전환: " + forced);
	}

	/**
	 * 숨긴 오브젝트를 숨긴 채로만 쓴 곳이 없다 (게임에서는 못 함, 42 문서 §4·§5): 이웃 레벨 GlobalData·전역 이벤트로 다시 돌려
	 * ATHN01D 선장 문 (열쇠 GlobalData:0 ← ATHN01E)·PAND01BB 석상 크랭크 (손잡이 GlobalData:3)·PAND00C 엘리베이터 레버
	 * (링 크랭크 3 개 정렬 + PAND01BB GlobalData:4 + PAND00D 203/202 → 205 → LeverOn) 가 모두 보이는 상태에서 쓰인다.
	 */
	@Test
	void hiddenObjectsAllRevealed() {
		List<String> hidden = v.results.values().stream().flatMap(r -> r.interactions.stream().filter(x -> x.startsWith(TraversalVerifier.HIDDEN_USE))
				.map(x -> r.level + " " + x.substring(TraversalVerifier.HIDDEN_USE.length()))).toList();
		assertEquals(List.of(), hidden);
		assertTrue(v.results.get("ATHN01D").withNeighbours.contains("ATHN01E"));
		assertTrue(v.results.get("PAND01BB").withNeighbours.contains("PAND00C"));
		assertTrue(v.results.get("PAND00C").interactions.stream().anyMatch(x -> x.startsWith("RING goring1crank")), "링 크랭크 풀이");
	}

	/** 헤드리스 보스전 (HeadlessBoss): 히드라·메두사 여왕·아레스 1/3 이 원본 데이터 흐름으로 격파된다. */
	@Test
	void bossesDefeatedHeadless() {
		for (String l : List.of("ATHN01E", "ATHN02D", "ATHN08A", "ATHN08C")) {
			LevelResult r = v.results.get(l);
			assertTrue(r.bossDefeated, l + " " + r.bossPhases);
		}
	}

	/** PAND02F → PAND02H: 발판이 아니라 PAND02F 바닥 구멍으로 떨어져 PAND02H 구덩이 바닥에 닿는다 (LoadCheckH 볼륨 안). */
	@Test
	void pand02hArrivalIsAFall() {
		var a = v.results.get("PAND02H").arrivals.stream().filter(x -> x.from().equals("PAND02F")).toList();
		assertFalse(a.isEmpty());
		assertTrue(a.stream().allMatch(TraversalVerifier.Arrival::ok), a.toString());
		assertTrue(a.stream().anyMatch(x -> x.detail().contains("낙하")), a.toString());
		List<String> bad = v.results.values().stream().flatMap(r -> r.arrivals.stream().filter(x -> !x.ok()).map(x -> x.from() + "→" + r.level)).toList();
		assertTrue(bad.isEmpty(), "도착 검사 실패: " + bad);
	}

	private static boolean unforced(String from, String to) {
		return v.results.get(from).edges.stream().anyMatch(e -> e.target().equals(to) && !e.forced());
	}

	@Test
	void report() {
		String md = TraversalReport.markdown(v);
		assertTrue(md.contains("| ATHN01A |"));
		Map<String, LevelResult> r = v.results;
		assertTrue(r.get("ATHN01A").spawnGround);
	}
}
