package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.anim.WeaponAttach;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 아르테미스의 검 히트박스 (43 §34): CDV_berserkBlade 구 2 개 (부위 10) 를 히어로 rWeapIH 에 (원작 0x19C240 → 0x19C388 "RWEAPIH").
 * BER* 공격 (부위 10) 은 이 구의 스윕으로 맞는다 — 예전 정면 근사 구 (앞 55, 반지름 55) 처럼 앞 100 의 적을 맞히지 않고, 칼날이 지나는 옆·뒤는 맞힌다.
 */
class ArtemisHitVolumeTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_HERO0.WAD")) && Files.exists(Content.file(rom, "R_BRSRK0.WAD")), "gow-rom 없음");
		return rom;
	}

	@Test
	void berserkBladeSpheresOnRightInHandJoint() throws Exception {
		Path rom = rom();
		WadFile hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		for (int stage = 0; stage < 3; stage++) {
			WeaponAttach w = WeaponAttach.berserkBlade(WadFile.load(Content.file(rom, "R_BRSRK" + stage + ".WAD")));
			assertArrayEquals(new int[]{10}, w.cdv.materialPart, "CDV_berserkBlade 재질 = PartType 10 (단계 " + stage + ")");
			assertEquals(2, w.cdv.balls.size());
			assertEquals(35.52f, w.cdv.balls.get(0)[3], 0.01f);
		}
		HitVolumes v = HitVolumes.forHero(hero, sk, null, WadFile.load(Content.file(rom, "R_BRSRK0.WAD")));
		assertTrue(v.hasPart(HitVolumes.PART_ARTEMIS));
		float[][] idle = devple.gow.tools.anim.Pose.idle(sk).world(sk);
		v.update(idle, new Vector3f(), 0);
		List<float[]> s = v.spheres(HitVolumes.PART_ARTEMIS);
		assertEquals(2, s.size());
		float[] ih = idle[sk.indexOf("rWeapIH")];
		for (float[] b : s) {
			assertEquals(8.88f, b[3], 0.02f, "검 관절 idle 배율 0.25 × 35.52");
			float d = (float) Math.sqrt((b[0] - ih[12]) * (b[0] - ih[12]) + (b[1] - ih[13]) * (b[1] - ih[13]) + (b[2] - ih[14]) * (b[2] - ih[14]));
			assertTrue(d < 23f, "rWeapIH 에서 칼날 따라 (≤ 22.2): " + d);
		}
		assertTrue(v.ownSpheres().stream().noneMatch(b -> Math.abs(b[3] - 8.88f) < 0.02f), "부착 무기 구는 몸 충돌 구가 아니다");
	}

	/**
	 * 부위 10 BER* 콤보 □□□□ (BERCOMBOH1 → H2 → H1 → H4, 영상 4062–4085 처럼): 적 위치별 적중 수, 실제 구 / 예전 근사.
	 * 실제 구 (해골 몸 구 r 19.8 · 23.1): 앞 40 · 60 은 맞고 75 · 100 은 안 맞으며, 칼날이 지나는 옆 40 · 뒤 40 도 맞는다.
	 * 예전 근사 (앞 55, 반지름 55): 앞 100 까지 맞고 옆 · 뒤는 못 맞혔다.
	 */
	@Test
	void berComboReachFollowsTheBlade() throws Exception {
		int[][] at = {{0, -40}, {0, -60}, {0, -75}, {0, -100}, {40, 0}, {-40, 0}, {0, 40}};
		int[] real = new int[at.length], old = new int[at.length];
		StringBuilder log = new StringBuilder();
		for (int i = 0; i < at.length; i++) {
			real[i] = hits(at[i][0], at[i][1], true, PadButton.SQUARE);
			old[i] = hits(at[i][0], at[i][1], false, PadButton.SQUARE);
			log.append(String.format("(%d, %d) 구 %d 근사 %d; ", at[i][0], at[i][1], real[i], old[i]));
		}
		System.out.println(log);
		assertTrue(real[0] >= 2 && real[1] >= 2, "앞 40 · 60 은 칼날 안: " + log);
		assertEquals(0, real[2] + real[3], "앞 75 · 100 은 칼날 밖: " + log);
		assertTrue(old[3] >= 2, "예전 근사는 앞 100 도 맞혔다: " + log);
		for (int i = 4; i < at.length; i++) {
			assertTrue(real[i] >= 1, "칼날이 지나는 옆 · 뒤: " + log);
			assertEquals(0, old[i], "예전 근사는 옆 · 뒤를 못 맞혔다: " + log);
		}
	}

	/** 영웅 원점, 정면 −Z. 해골 병사 (R_SKS) 를 (x, z) 에 두고 L1+R1 장착 뒤 버튼 네 번 — 적중 수. */
	private static int hits(float x, float z, boolean volumes, PadButton button) throws Exception {
		Path rom = rom();
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		Animator animator = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(animator);
		boolean[] equipped = {false};
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 2; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return equipped[0]; }
			public boolean artemisOwned() { return true; }
			public int selectedMagic() { return 0; }
			public int classId() { return -1; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), animator, owner);
		if (volumes) {
			c.setHitVolumes(HitVolumes.forHero(heroWad, sk, null, WadFile.load(Content.file(rom, "R_BRSRK0.WAD"))));
		}
		EnemyFighter min = EnemyFactory.load(Content.file(rom, "R_SKS.WAD"), perm, -1).spawn(); // 구멍 방 해골 (PAND01A HoleSkelly)
		min.pos.set(x, 0, z);
		min.yaw = (float) Math.atan2(x, z); // 영웅 쪽을 봄 (정면 −Z 규약)
		c.setTarget(min);
		int[] hits = {0};
		c.onLog(s -> {
			if (s.contains("적중")) {
				hits[0]++;
			}
		});
		ScriptedInput in = new ScriptedInput(0, List.of(
				new ScriptedInput.Step(0.1f, 0.4f, PadButton.L1),
				new ScriptedInput.Step(0.3f, 0.05f, PadButton.R1),
				new ScriptedInput.Step(1.6f, 0.05f, button),
				new ScriptedInput.Step(2.0f, 0.05f, button),
				new ScriptedInput.Step(2.4f, 0.05f, button),
				new ScriptedInput.Step(2.8f, 0.05f, button)));
		float dt = 1 / 60f;
		for (float t = 0; t < 4.5f; t += dt) {
			in.advance(dt);
			c.update(in, dt);
			if (c.activeScript(0xe9283e7e) != null) {
				equipped[0] = true;
			}
			if (c.active()) {
				ctl.updateCombat(c, in, 0, dt, null);
			} else {
				ctl.update(in, 0, dt);
			}
			ctl.position.set(0, 0, 0); // 루트 이동 없이 제자리 (거리 비교) — 구는 Combatant.update 가 갱신
			min.update(dt);
			min.pos.set(x, 0, z);
		}
		assertTrue(equipped[0], "BERSERKENTER 장착");
		return hits[0];
	}
}
