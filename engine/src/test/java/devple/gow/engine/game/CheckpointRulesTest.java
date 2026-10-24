package devple.gow.engine.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원작 CheckPoint (GameMgr 메시지 0, 0x1B0234) 거름 — 43 문서 §19 (영상 7045–7053: ATHN05B 발코니 받침대 옆에서 기둥으로 뛰다 떨어져 죽고 그 자리에서 다시 시작).
 * 체력 (+0x178) ≥ 1, 세계 시작 (0x29D924) 뒤 1 초, 같은 엔티티 (0x303D74) 는 5 초 (0x303D78) 안에 다시 받지 않는다.
 */
class CheckpointRulesTest {

	@Test
	void checkpointNeedsHealthAndOneSecondOfWorldTime() {
		Object a = new Object();
		assertTrue(GameScene.checkpointPasses(a, null, 0, 100, 1.5f));
		assertFalse(GameScene.checkpointPasses(a, null, 0, 0.5f, 10), "체력 < 1 (죽는 중) 은 받지 않음");
		assertFalse(GameScene.checkpointPasses(a, null, 0, 100, 0.9f), "세계 시작 뒤 1 초 안");
	}

	@Test
	void sameEntityIsDebouncedForFiveSecondsOthersAreNot() {
		Object a = new Object(), b = new Object();
		assertFalse(GameScene.checkpointPasses(a, a, 10, 100, 14.9f), "같은 엔티티 5 초 안");
		assertTrue(GameScene.checkpointPasses(a, a, 10, 100, 15f), "5 초 뒤");
		assertTrue(GameScene.checkpointPasses(b, a, 10, 100, 10.1f), "다른 엔티티는 곧바로");
	}
}
