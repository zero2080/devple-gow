package devple.gow.engine.level;

import devple.gow.engine.game.SceneNav;
import devple.gow.engine.nav.NavWorld;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 다른 슬롯 레벨의 움직이는 오브젝트 충돌 (43 문서 §13.8 → 이 레벨 발판과 같은 규칙): DEST02 에서 본 DEST01 `goenddoor`.
 * 닫힌 문 = CDV hull 이 막고, 이웃 스크립트가 연 문 (`EndDoor-AnimOpen` Animator + `EndDoor-DoorCDOff` Vis 충돌 끔) 은 막지 않는다.
 */
class NeighbourDoorCollisionTest {

	private static LevelData load(String level) throws Exception {
		Path wad = Path.of(System.getProperty("gow.rom", "../gow-rom"), level + ".WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		return new LevelData(WadFile.load(wad));
	}

	/** 문 hull 안 (문틀 사이, 크레토스 허리 높이) — 이음 도착점 (2429, 516, 6496) 바로 앞. */
	private static final Vector3f IN_DOOR = new Vector3f(2430, 536, 6479);

	/** 도착점에서 DEST01 쪽으로 걸을 때 구 (r 12, 발 + 20) 가 문에 막혀 밀려난 최대 거리. */
	private static float blockedAlongWalk(NavWorld nw) {
		Vector3f a = new Vector3f(2429, 536, 6500), b = new Vector3f(2420, 536, 6440);
		float worst = 0;
		for (int i = 0; i <= 30; i++) {
			Vector3f c = new Vector3f(a).lerp(b, i / 30f), c0 = new Vector3f(c);
			nw.collideBodies(c, 12, Set.of());
			worst = Math.max(worst, c.distance(c0));
		}
		return worst;
	}

	@Test
	void neighbourDoorBlocksWhenClosedAndNotWhenOpenedByNeighbourScript() throws Exception {
		LevelData dest01 = load("DEST01"), dest02 = load("DEST02");
		Platforms[] ps = new Platforms[1];
		ScriptWorld[] sw = new ScriptWorld[1];
		ScriptListener l = new ScriptListener() {
			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				SceneNav.applyAnimator(sw[0], ps[0], source, objects, anim);
			}

			@Override
			public void vis(ScriptEntity source, List<String> objects, int[] values) {
				SceneNav.applyCollision(ps[0], objects, values);
			}
		};
		sw[0] = new ScriptWorld(dest01, new ScriptHost(), l);
		Set<String> owners = SceneNav.animatorOwners(sw[0]);
		ps[0] = Platforms.of(dest01, owners, owners);
		// SceneNav.setOther 와 같은 구성: 이 레벨 정적 + 다른 슬롯 정적 + 다른 슬롯 발판
		NavWorld nw = new NavWorld(new LevelCollision(dest02.sheets));
		nw.setOther(new LevelCollision(dest01.sheets));
		nw.setNeighbourPlatforms(ps[0]);

		// 닫힌 문: 정적 충돌에는 문이 없고 (예전 [근사] = 통과), CDV hull 이 막는다
		assertFalse(nw.collideSphere(new Vector3f(IN_DOOR), 2), "문은 정적 시트가 아님");
		assertTrue(nw.collideBodies(new Vector3f(IN_DOOR), 2, Set.of()), "닫힌 이웃 문 hull 이 막음");
		assertTrue(blockedAlongWalk(nw) > 20, "닫힌 문 앞에서 밀려남");
		Vector3f high = new Vector3f(IN_DOOR.x, 620, IN_DOOR.z); // 닫힌 문 hull y 516–668
		assertTrue(nw.collideBodies(new Vector3f(high), 2, Set.of()));

		// 이웃 스크립트가 문을 연다: Animator (조건 LevelData:0 == 3 = 세이렌 셋 — 조건 밖이라 직접) + Vis "DoorCDOff"
		ScriptEntity open = sw[0].find("EndDoor-AnimOpen"), cdOff = sw[0].find("EndDoor-DoorCDOff");
		assertNotNull(open);
		assertNotNull(cdOff);
		SceneNav.applyAnimator(sw[0], ps[0], open, List.of(open.ownerName()), "!0");
		Platforms.Body door = ps[0].body("goenddoor");
		assertTrue(door.playing(), "EndDoor 애니 재생");
		for (int i = 0; i < 60 * 4; i++) {
			ps[0].update(1 / 60f);
		}
		assertFalse(door.playing(), "모드 8 = 한 번 (3 초)");
		assertFalse(nw.collideBodies(new Vector3f(high), 2, Set.of()), "hull 이 애니 자세를 따라 104 내려감 (y 412–564)");
		assertTrue(nw.collideBodies(new Vector3f(IN_DOOR), 2, Set.of()), "충돌이 켜진 채면 남은 윗부분이 아직 막음");
		// Vis DoorCDOff (조건은 문 열기와 같은 세이렌 상태라 직접): 슬롯 0..7 → 리스너 값 (ScriptWorld.fire 타입 7 과 같은 순서)
		int[] v = new int[11];
		for (int k = 0; k < 8; k++) {
			v[k] = sw[0].slot(cdOff, k) instanceof Number n ? n.intValue() : 0;
		}
		assertEquals(2, v[3], "Attr11_0 = 2 (충돌 끔)");
		SceneNav.applyCollision(ps[0], List.of(cdOff.ownerName()), v);
		assertFalse(door.solid);
		assertFalse(nw.collideBodies(new Vector3f(IN_DOOR), 2, Set.of()), "열린 이웃 문은 막지 않음");
		assertEquals(0f, blockedAlongWalk(nw), 1e-4f, "문을 지나 DEST01 쪽으로");

		// 이음 넘기기: 열린 문 + 충돌 끔 상태가 새 Platforms 에 그대로 (원작은 두 슬롯이 그대로 산다)
		Platforms again = Platforms.of(dest01, owners, owners);
		again.restore(ps[0].states());
		assertFalse(again.body("goenddoor").solid, "충돌 끔도 넘겨받음");
		nw.setNeighbourPlatforms(again);
		assertFalse(nw.collideBodies(new Vector3f(IN_DOOR), 2, Set.of()));
	}
}
