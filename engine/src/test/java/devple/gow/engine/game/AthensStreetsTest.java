package devple.gow.engine.game;

import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.Hittable;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 아테네 거리 ATHN03A · ATHN03C (PS2 영상 5136–5716, 43 문서 §17): 해골 궁수 화살 (SCR_ArcherShoot · TARGET_FX → ArrowEmitter),
 * 궁수 SHOOT 의 블렌드 그룹 애니, 이웃 슬롯 적 리소스 (ATHN03C 고르곤).
 */
class AthensStreetsTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static Path rom(String name) {
		Path p = Content.file(ROM, name);
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return p;
	}

	private static WadFile perm() throws Exception {
		return WadFile.load(rom(Region.of(ROM).perm()));
	}

	/**
	 * SCR_ArcherShoot (0x18DAA8 → 매 프레임 0x18D500): 겨눔 값 (수평 각 / 25°, [−1, 1]) 까지 0.02 / 프레임, 그동안 당김 += 0.01 (첫 프레임 −0.99),
	 * 그다음 당김 += 0.05 / 프레임이 이벤트 b 에 닿으면 쏜다. 바로 앞 = 25 프레임, 25° 이상 옆 = 64 프레임, 폭발 화살 (b = 0) 바로 앞 = 21 프레임.
	 */
	@Test
	void archerDrawTimeFollowsAimAngle() {
		assertEquals(25 / 60f, GameScene.archerShotDelay(0f, 0.2f), 1e-6f);
		assertEquals(64 / 60f, GameScene.archerShotDelay(0.43633f, 0.2f), 1e-6f);
		assertEquals(64 / 60f, GameScene.archerShotDelay(-2f, 0.2f), 1e-6f, "겨눔 값은 [−1, 1] 로 자름");
		assertEquals(21 / 60f, GameScene.archerShotDelay(0f, 0f), 1e-6f);
		float half = GameScene.archerShotDelay(0.218165f, 0.2f); // 12.5° → 겨눔 0.5 = 25 프레임, 당김 −0.75 → 0.2 = 19 프레임
		assertEquals(44 / 60f, half, 1e-6f);
	}

	/**
	 * 탄도 해 0x2223C8: θ 를 0 부터 π/4 걸음으로 8 번 반으로 줄여 낮은 탄도로 대상 높이를 맞춘다 (속도 크기는 그대로, 단위/초 = m/s × 16).
	 */
	@Test
	void ballisticLaunchReachesTargetOnTheLowArc() {
		Vector3f from = new Vector3f(0, 0, 0), to = new Vector3f(800, 80, -300);
		float speed = 30f, g = 9.82f;
		Vector3f v = ArrowShots.launch(from, to, speed, g);
		assertEquals(speed * 16f, v.length(), 0.05f);
		float horiz = (float) Math.hypot(to.x, to.z), vh = (float) Math.hypot(v.x, v.z);
		float t = horiz / vh;
		float y = v.y * t - 0.5f * g * 16f * t * t;
		// 돌려주는 각 = 마지막으로 평가한 각 (7 번째 조정 전) → 오차 ≤ π/4 / 64 라디안 → 높이 오차 ≤ 거리 × 그 각 / cos²θ
		double theta = Math.atan2(v.y, vh);
		double tol = horiz * (Math.PI / 4 / 64) / (Math.cos(theta) * Math.cos(theta));
		assertEquals(to.y, y, (float) tol, "8 번 반으로 줄인 각의 오차 안 (" + tol + ")");
		assertTrue(Math.abs(to.y - y) > 1f, "마지막 조정은 쓰지 않는다 (원작 0x222524) — 정확히 맞지는 않음");
		assertTrue(theta < Math.PI / 4, "낮은 탄도");
		assertEquals(to.x / horiz, v.x / vh, 1e-4f, "수평 방향 = 대상 쪽");
		// 중력이 없으면 곧장
		Vector3f d = ArrowShots.launch(from, to, speed, 0f).normalize();
		assertEquals(new Vector3f(to).normalize().y, d.y, 0.01f);
	}

	/** 맞은 기록만 남기는 영웅 (피격 볼륨 없음 → 몸 구 폴백). */
	private static final class Target implements Hittable {
		final Vector3f pos;

		Target(Vector3f pos) {
			this.pos = pos;
		}

		@Override
		public Vector3f position() {
			return pos;
		}

		@Override
		public float hp() {
			return 100;
		}

		@Override
		public int classId() {
			return 0;
		}

		@Override
		public boolean invulnerable() {
			return false;
		}

		@Override
		public Result receive(CombatFile.Attack attack, devple.gow.engine.combat.Combatant attacker, float damage, Vector3f knock) {
			return Result.HIT;
		}
	}

	/**
	 * R_SKA ArrowEmitter 20 "skelly archer" (화살 20: 100 m/s, 중력 9.82 (Gravity Flag 1), 피해 2, 퍼짐 #1bf49679 = 0.1 m, 겨눔 관절 rWrist):
	 * 궁수 손에서 20 m 떨어진 영웅 몸 중심을 향해 쏘면 그 몸 구에 맞고 "영웅" 으로 멈춘다. 폭발 화살 33 은 Concussion 33 · 퍼짐 1.5 m.
	 */
	@Test
	void archerArrowFliesIntoTheHeroBody() throws Exception {
		Tweaks tw = new Tweaks();
		tw.load(perm());
		tw.load(WadFile.load(rom("R_SKA.WAD")));
		assertEquals(2, tw.i16("/TweakTemplates/Arrow/020/", "Damage", -1));
		assertEquals(33, tw.i16("/TweakTemplates/Arrow/033/", "Concussion", -1));
		assertEquals("rWrist", tw.s("/TweakTemplates/ArrowEmitter/020/", "Aim Joint", null));
		EnemyFactory.Kind ska = EnemyFactory.load(rom("R_SKA.WAD"), perm(), -1);
		EnemyFighter archer = ska.spawn();
		Vector3f heroFeet = new Vector3f(0, 0, -320);
		Vector3f target = new Vector3f(heroFeet).add(0, 16 * 2.2f * 0.5f, 0);
		Vector3f origin = new Vector3f(0, 24, 0);
		ArrowShots shots = new ArrowShots();
		List<ArrowShots.Shot> out = shots.fireAt(tw, 20, origin, target, new Vector3f(), archer.combat(), null, new Random(1));
		assertEquals(1, out.size(), "Count 1");
		ArrowShots.Shot s = out.getFirst();
		assertEquals(1600f, s.vel.length(), 0.5f, "100 m/s × 16");
		List<Vector3f> hits = new ArrayList<>();
		Target hero = new Target(heroFeet);
		var t = new ArrowShots.Targets(null, null, null, List.of(), null, null, null, null, null, null, hero, (sh, at) -> hits.add(at));
		for (int i = 0; i < 120 && !s.dead; i++) {
			shots.update(1 / 60f, t);
		}
		assertTrue(s.dead, "멈춤");
		assertEquals("영웅", s.hit);
		assertEquals(1, hits.size());
		assertEquals(2f, s.damage(), 1e-6f);
		assertEquals(heroFeet.z, hits.getFirst().z, 20f, "몸 앞에서 맞음");
	}

	/**
	 * 궁수 SHOOT (기술 1) 의 애니 해시는 블렌드 그룹 "shoot" (플래그 0x20, Ashoot00/01/02 = 왼쪽·가운데·오른쪽) 을 가리킨다 — 그룹 이름도 찾아야
	 * 기술이 시작 프레임에 끝나지 않고 SCR_ArcherShoot (0x63F805BF, b 0.2, 이미터 20) 과 SND_BOW_FIRE 가 난다 (원작 0x1F6FC8 → 0x107200).
	 */
	@Test
	void archerShootMoveUsesTheBlendGroup() throws Exception {
		EnemyFactory.Kind ska = EnemyFactory.load(rom("R_SKA.WAD"), perm(), -1);
		AnimationSet.Group shoot = ska.anims().groups.stream().filter(g -> "shoot".equalsIgnoreCase(g.name)).findFirst().orElseThrow();
		assertNotEquals(0, shoot.flags & 0x20, "블렌드 그룹");
		var data = ska.spawn().combat().data();
		var m = data.move(1);
		assertNotNull(m);
		Animation a = data.animation(m);
		assertNotNull(a, "SHOOT 애니");
		assertTrue(shoot.acts.contains(a), "그룹의 act: " + a.name);
		assertEquals(1, a.blendIndex, "가운데 (블렌드 번호 1)");
		assertTrue(m.events().stream().anyMatch(ev -> ev.type() == 8 && ev.hash() == GameScene.SCR_ARCHER_SHOOT && ev.index() == 20),
				"SCR_ArcherShoot 이미터 20");
	}

	/**
	 * 슬롯마다 RSRCS 리소스 (Tag_Resources 0x1BB928): ATHN03A 는 R_SKA·R_MI2 만, ATHN03C 는 R_MEW (고르곤) 도 — ATHN03A 에 있을 때 이웃 슬롯
	 * ATHN03C 스크립트가 내는 고르곤 (템플릿 60) 은 이웃의 R_MEW 로 만든다 (영상 5130–5172).
	 */
	@Test
	void neighbourSlotResourcesBringTheGorgons() throws Exception {
		List<String> a = EnemyFactory.resourceWads(WadFile.load(rom("ATHN03A.WAD")));
		List<String> c = EnemyFactory.resourceWads(WadFile.load(rom("ATHN03C.WAD")));
		assertFalse(a.contains("R_MEW"), "ATHN03A " + a);
		assertTrue(c.contains("R_MEW"), "ATHN03C " + c);
		EnemyFactory.Kind mew = EnemyFactory.load(rom("R_MEW.WAD"), perm(), -1);
		assertTrue(mew.hasTemplate(60), "고르곤 템플릿 60");
	}
}
