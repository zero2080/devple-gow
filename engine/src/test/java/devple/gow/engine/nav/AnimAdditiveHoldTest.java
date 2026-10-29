package devple.gow.engine.nav;

import devple.gow.tools.anim.AnimTrack;
import devple.gow.tools.content.Content;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 43 §52.1-1: 가산 트랙의 hold 는 마지막 변화량을 hold − 1 프레임 더 더한다 (재생기 0x111760 의 트랙 창 [start, start + count + hold − 1] ·
 * 가산 이동 0x231E40 의 칸 묶음 count − 1). PAND04F 승강기 goelevator act "Elevator" 관절 1 y 는 가산 두 트랙 (1 + 11 프레임 · hold 193,
 * 204 + 3 프레임 · hold 191) 으로 1.918/프레임씩 올라 399 프레임의 절대 키 664.9 에 닿는다. 예전 엔진은 hold 를 무시해 13 초 동안 거의 서 있다가
 * 끝에서 한 번에 739 를 뛰어 승강기에 탄 영웅이 떨어졌다 (영상 5 1500–1560 광기의 절벽 꼭대기 승강기).
 */
class AnimAdditiveHoldTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void elevatorRisesSteadilyThroughAdditiveHold() throws Exception {
		Path p = Content.file(ROM, "PAND04F.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData lv = new LevelData(WadFile.load(p));
		Platforms ps = Platforms.of(lv, Set.of("goelevator"));
		Platforms.Body b = ps.body("goelevator");
		assertTrue(ps.play("goelevator", "!0", 8, 1f, 0f));
		var a = b.anim;
		assertEquals("Elevator", a.name);
		AnimTrack hold = a.posTracks.stream().filter(t -> t.additive() && t.start() == 1 && t.count() == 11).findFirst().orElseThrow();
		assertEquals(193, hold.hold(), "데이터: 가산 트랙 hold");

		float y0 = jointY(b, 0f);
		float prev = jointY(b, 1f);
		for (float t = 2f; t <= 13f; t += 1f) {
			float y = jointY(b, t);
			assertTrue(y - prev > 50f && y - prev < 65f, "초당 ≈ 57.5 씩 오름 (1.918 × 30) t=" + t + " " + prev + " → " + y);
			prev = y;
		}
		float top = jointY(b, a.duration);
		assertEquals(y0 + 748f, top, 3f, "끝 = 절대 키 664.9 (시작 −81.2 에서 +746) 와 이어짐");
		assertTrue(top - jointY(b, a.duration - 0.1f) < 10f, "마지막 프레임에 뛰지 않음");
	}

	private static float jointY(Platforms.Body b, float t) {
		b.time = t;
		b.pose();
		return b.joints[1][13];
	}
}
