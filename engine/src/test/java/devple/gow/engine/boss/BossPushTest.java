package devple.gow.engine.boss;

import devple.gow.engine.combat.BodyPush;
import devple.gow.engine.combat.EnemyFighter;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 보스 생물 밀어내기 (32 문서 §생물끼리 밀어내기 — 보스): 보스도 생물 범주 (Enemy1, +0xF4 1|2) 라 영웅을 민다 (단단하면).
 * 자기 밀기는 Sold +0x1C 비트 1 (히드라 250–253, 악령 크라토스 660) 과 BMI Think 0x1C0A48 (매 프레임 +0x2D8 비트 31) 이 끈다 [확실].
 */
class BossPushTest {

	private static float flat(Vector3f a, Vector3f b) {
		return (float) Math.hypot(a.x - b.x, a.z - b.z);
	}

	/** 영웅 (지상, 크라토스 Sold_000) 을 보스 정면 dist 에 두고 한 번 민다 → 밀린 뒤 수평 거리. */
	private static float heroPushedFrom(EnemyFighter boss, float dist) {
		Vector3f p = new Vector3f(boss.pos).add(dist, 0, 0);
		BodyPush.Body hero = BodyPush.hero(p, () -> false, null);
		for (int i = 0; i < 60; i++) {
			BodyPush.push(hero, p, List.of(boss), null);
			p.y = boss.pos.y;
		}
		return flat(p, boss.pos);
	}

	@Test
	void medusaQueenPushesHeroAndItself() throws Exception {
		BossRig r = MedusaQueenFightTest.spawned();
		EnemyFighter b = r.fight.boss();
		assertFalse(b.nav().noPush(), "Sold 062 +0x1C = 4");
		assertTrue(b.solid());
		assertEquals(11.2f, b.bodyRadius(), 1e-3f);
		float d = heroPushedFrom(b, 5);
		assertTrue(d > 15, "영웅이 메두사 캡슐 밖으로: " + d);
		// 메두사도 자기 갱신에서 영웅 밖으로 자신을 민다 — 움직일 때만 (서 있으면 0x1EBB38 정지 → 밀지 않음)
		b.setCrowd(List::of);
		for (int i = 0; i < 120; i++) {
			b.nav().rootMoved = false;
			b.nav().update(b.pos, new Vector3f(), null, false, 1 / 60f); // 감속해 멈춤
		}
		assertEquals(0f, b.nav().speed(), 0f);
		Vector3f heroPos = new Vector3f(b.pos).add(4, 0, 0);
		BodyPush.Body hero = BodyPush.hero(heroPos, () -> false, null);
		b.setCrowd(() -> List.of(hero, b));
		Vector3f before = new Vector3f(b.pos);
		b.nav().update(b.pos, new Vector3f(), null, false, 1 / 60f);
		assertEquals(0f, flat(b.pos, before), 1e-4f, "서 있는 메두사는 자기를 밀지 않음");
		b.nav().rootMoved = true; // 기술 루트 모션으로 움직이는 중
		b.nav().update(b.pos, new Vector3f(), null, false, 1 / 60f);
		assertTrue(flat(b.pos, before) > 5, "메두사가 밀려남 " + b.pos + " / " + before);
	}

	@Test
	void bmiThinkTurnsOffOwnPushButStaysSolid() throws Exception {
		BossRig r = BmiFightTest.spawned();
		EnemyFighter b = r.fight.boss();
		assertTrue(b.nav().noPush(), "Think 0x1C0AE8: +0x2D8 |= 0x80000000");
		assertTrue(b.solid());
		assertTrue(b.bodyRadius() >= 136f - 1e-3f, "Sold 200 반폭 8.5 → 136 (관절 상자는 줄지 않음)");
		assertTrue(heroPushedFrom(b, 60) > 100, "영웅은 BMI 에게 밀린다");
		Vector3f heroPos = new Vector3f(b.pos).add(30, 0, 0);
		BodyPush.Body hero = BodyPush.hero(heroPos, () -> false, null);
		b.setCrowd(() -> List.of(hero, b));
		Vector3f before = new Vector3f(b.pos);
		b.nav().rootMoved = true; // 움직여도
		b.nav().update(b.pos, new Vector3f(), null, false, 1 / 60f);
		assertEquals(0f, flat(b.pos, before), 1e-4f, "BMI 는 자기를 밀지 않음");
	}

	@Test
	void hydraHeadsDoNotPushThemselves() throws Exception {
		BossRig r = HydraFightTest.spawned();
		EnemyFighter b = r.fight.boss();
		assertEquals(250, b.ai1());
		assertTrue(b.nav().noPush(), "Sold 250 +0x1C = 0x0E (비트 1)");
		assertTrue(b.solid());
		assertEquals(112f, b.nav().bodyRadius(), 1e-3f);
		// 관절 상자 0x1E6DF0 은 플레이어 (+0x174 비트 31) 만 — Sold +0xA4 BHA01JOCHEA1 이 있어도 히드라는 Sold 상자 그대로
		assertEquals(0f, b.refX(), 0f);
		assertEquals(0f, b.refZ(), 0f);
		assertEquals(112f, b.bodyRadius(), 1e-3f);
	}

	@Test
	void aresPushesItself() throws Exception {
		BossRig r = AresFightTest.spawned("ATHN08A");
		EnemyFighter b = r.fight.boss();
		assertFalse(b.nav().noPush(), "Sold 600 +0x1C = 4");
		assertEquals(16f * 1.15f, b.nav().bodyRadius(), 0.05f, "반폭 1.0 × 개체 배율");
		assertTrue(heroPushedFrom(b, 10) > 25);
	}
}
