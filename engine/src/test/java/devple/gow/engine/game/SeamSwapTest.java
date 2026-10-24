package devple.gow.engine.game;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.fx.FxEnvironment;
import devple.gow.engine.level.CameraRig;
import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.NavWorld;
import devple.gow.tools.content.Content;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 같은 씬 안 이음 (GameScene.seamSwap, 43 §27) 이 쓰는 부품: 원작 LoadCheck 0x1BCA10 은 현재 레벨 이름만 바꾸고 두 슬롯의 나머지는 그대로라, 역할만 바꿔도
 * 이어지는 상태 (카메라 상태 링·정적 충돌·이펙트 등록·안개) 가 같아야 한다. GL 없이 되는 것만.
 */
class SeamSwapTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static WadFile wad(String level) throws Exception {
		Path p = Content.file(ROM, level + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	/** 같은 이름의 GO 를 가진 두 레벨 WAD: 나중에 더한 쪽이 이기고, 뺀 WAD 가 덮었던 이름은 남은 WAD 정의로, promote 는 다시 해석 없이 맨 뒤로. */
	@Test
	void effectLibraryRemoveRestoresShadowedNamesAndPromoteReorders() throws Exception {
		WadFile a = wad("ATHN03A"), c = wad("ATHN03C");
		EffectLibrary lib = new EffectLibrary();
		lib.add(a);
		Set<String> inA = new HashSet<>(lib.names());
		lib.add(c);
		String shared = lib.names().stream().filter(inA::contains).filter(n -> lib.resolve(n).wad() == c).findFirst().orElse(null);
		Assumptions.assumeTrue(shared != null, "두 WAD 에 같은 이름 GO 없음");
		assertSame(c, lib.resolve(shared).wad(), "나중 WAD 가 이김");
		assertTrue(lib.promote(a));
		assertSame(a, lib.resolve(shared).wad(), "promote = 맨 뒤 (이음으로 다시 현재가 된 레벨)");
		assertFalse(lib.remove(a).isEmpty());
		assertSame(c, lib.resolve(shared).wad(), "뺀 WAD 가 덮었던 이름은 남은 WAD 정의로");
		assertTrue(lib.remove(a).isEmpty(), "두 번 빼도 무해");
		lib.remove(c);
		assertNull(lib.resolve(shared));
		assertEquals(0, lib.size());
	}

	/** 정적 충돌 순서만 바뀐다 (현재 먼저) — 바닥 질의 결과는 같다. */
	@Test
	void navWorldSwapStaticsKeepsBothSheets() throws Exception {
		LevelCollision c5 = new LevelCollision(new LevelData(wad("ATHN05C")).sheets), d5 = new LevelCollision(new LevelData(wad("ATHN05D")).sheets);
		NavWorld w = new NavWorld(c5);
		w.setOther(d5);
		float before = w.groundHeight(-1300, 900, 1840, 200);
		w.swapStatics();
		assertSame(d5, w.statics().get(0));
		assertSame(c5, w.statics().get(1));
		assertEquals(before, w.groundHeight(-1300, 900, 1840, 200), 1e-3f, "05D 복도 바닥 (두 슬롯)");
		w.setOther(null);
		w.swapStatics();
		assertEquals(1, w.statics().size(), "다른 슬롯 없으면 그대로");
	}

	/**
	 * 카메라 리그의 현재 ↔ 다른 슬롯만 바꾸면 상태 링·활성 목록·자세가 그대로 — 다음 갱신의 카메라가 이음 전 그대로 이어진다 (예전 handoff/resume 은 보간·꺼진
	 * 볼륨·질의 끔을 끊었다). ATHN05C 항아리 방 → 05D 횃불 복도 경계.
	 */
	@Test
	void cameraRigSwapLevelsContinuesTheShot() throws Exception {
		LevelData c5 = new LevelData(wad("ATHN05C")), d5 = new LevelData(wad("ATHN05D"));
		CameraRig a = new CameraRig(c5), b = new CameraRig(c5);
		a.setOther(d5);
		b.setOther(d5);
		Vector3f feet = new Vector3f(-1440, 864, 2000);
		for (int i = 0; i < 90; i++) { // 항아리 방에서 복도 쪽으로 걸어감
			feet.z -= 2;
			a.update(1 / 60f, feet, (float) Math.PI, false);
			b.update(1 / 60f, feet, (float) Math.PI, false);
		}
		assertTrue(a.swapLevels(), "다른 슬롯 있음");
		for (int i = 0; i < 60; i++) {
			feet.x += 2;
			a.update(1 / 60f, feet, (float) Math.PI / 2, false);
			b.update(1 / 60f, feet, (float) Math.PI / 2, false);
			assertEquals(b.current(), a.current(), "같은 카메라 (틱 " + i + ")");
			assertTrue(b.eye().distance(a.eye()) < 0.5f, "같은 눈 (틱 " + i + "): " + a.eye() + " / " + b.eye());
		}
		assertFalse(new CameraRig(c5).swapLevels(), "다른 슬롯 없으면 false");
	}

	/**
	 * 두 슬롯 안개 (43 §27): 다른 슬롯 환경은 현재 레벨 시계를 따르고 (층 안개 텍스처 흐름), 고른 환경의 안개를 가져다 쓴다 — 현재/다른 슬롯 역할과 무관한 하나.
	 * ATHN01A 갑판 (층 안개) · ATHN01B 선창 (안개 + 층 안개).
	 */
	@Test
	void fogSourceIsSharedBetweenSlots() throws Exception {
		LevelData a = new LevelData(wad("ATHN01A")), b = new LevelData(wad("ATHN01B"));
		FxEnvironment ea = new FxEnvironment(new WadAssets(Content.file(ROM, "ATHN01A.WAD")), a);
		FxEnvironment eb = new FxEnvironment(new WadAssets(Content.file(ROM, "ATHN01B.WAD")), b);
		Vector3f deck = new Vector3f(1580, 60, 1760);
		ea.update(3.5f, deck);
		assertTrue(ea.selectFogAt(deck), "갑판 눈: 01A 안개 칸");
		eb.follow(ea, deck);
		assertEquals(ea.time(), eb.time(), 1e-6f, "다른 슬롯 시계 = 현재 레벨 시계");
		float own = eb.backgroundFactor(), deckFog = ea.backgroundFactor();
		eb.setFogSource(ea);
		assertEquals(deckFog, eb.backgroundFactor(), 1e-6f, "고른 환경 (01A) 의 안개");
		assertEquals(ea.activeFog(), eb.activeFog());
		eb.setFogSource(eb);
		assertEquals(own, eb.backgroundFactor(), 1e-6f, "자기 자신 = 자기 안개");
	}
}
