package devple.gow.engine.combat;

import devple.gow.engine.nav.Platforms;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 밟고 선 GO CDV 윗면의 재질 피해 (43 §53): 원작 바닥 질의 0x1E3298 은 정적 시트와 GO 충돌 인스턴스를 같이 보고 (접촉 레코드 0x2C8F10, +0x24 GO →
 * 영웅 +0x24C), 같은 레코드를 접촉 0x1E1DF0 (0x1E3650) 에 넘겨 재질 "Damage" 를 대기 피해로 건다. PAND04C 가시 함정 gospikes (재질 lambert289 Damage 1024):
 * 레버 30 초 뒤 spikes-AniFor 로 솟는 가시가 바닥의 영웅을 들어 올려 죽인다 (영상 5화 1708).
 */
class FloorCdvDamageTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	@Test
	void risenSpikesFloorKills() throws Exception {
		Path p = ROM.resolve("PAND04C.WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		LevelData d = new LevelData(WadFile.load(p));
		BallHull h = d.hulls.get("CDV_spikes");
		assertEquals(1024, h.materialDamage[0]);
		assertEquals(0, h.materialAiDamage[0]);
		Platforms ps = Platforms.of(d, Set.of());
		assertNotNull(ps.body("gospikes"), "가시 CDV 는 몸");
		// 솟기 전: 방 바닥 (y ≈ 1483) 위 가시 몸 윗면이 바닥 아래 — 영웅 발 높이에서 가시 지지 없음
		float[] spot = spikeSpot(ps);
		assertNull(spot, "솟기 전에는 가시 윗면이 바닥 위로 나오지 않는다");
		assertTrue(ps.play("gospikes", "!0"));
		for (int i = 0; i < 600; i++) {
			ps.update(1 / 60f);
		}
		spot = spikeSpot(ps);
		assertNotNull(spot, "솟은 가시 윗면");
		Platforms.Support s = ps.floor(spot[0], 1700, spot[2], 400);
		assertEquals("gospikes", s.body().owner);
		assertTrue(s.y() > 1490, "가시가 바닥 (1483) 위로 " + s.y());
		assertEquals(new Platforms.Touch(1024, 0), s.touch(false), "영웅 Damage 1024");
		assertEquals(Platforms.Touch.NONE, s.touch(true), "적 AIDamage 0");
	}

	/** 방 (−800..−400, −2400..−2100) 에서 영웅 발 높이 (1483) 위로 솟은 gospikes 윗면 한 점, 없으면 null. */
	private static float[] spikeSpot(Platforms ps) {
		for (float x = -800; x <= -400; x += 8) {
			for (float z = -2400; z <= -2100; z += 8) {
				Platforms.Support s = ps.floor(x, 1700, z, 400);
				if (s != null && s.body().owner.equals("gospikes") && s.y() > 1488) {
					return new float[]{x, s.y(), z};
				}
			}
		}
		return null;
	}
}
