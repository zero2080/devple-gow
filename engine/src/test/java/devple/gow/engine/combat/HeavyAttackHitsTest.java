package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
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
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 강공격 (△ 계열) 판정 — 실제 R_PERMK 기술·R_HERO0 블레이드 CDV·적 CDV 로 영웅 둘레에 적을 세우고 기술을 낸다 (51 문서).
 * <ul>
 *   <li>여러 적: 원작 접촉 루프 (0x13B688 → 0x203150 → OnContact 0x203AF0) 는 공격 구에 닿은 모든 생물에 같은 처리 — 대상만이 아니다.</li>
 *   <li>공격 플래그 0x1 = 락온 대상만 (0x209BB8), 기술당 기록 32 쌍 (0x209AB4).</li>
 *   <li>△ 홀드 띄우기: COMBO6A [0–0.30] △ 홀드 (mode 3) → COMBO7A → COMBO4D (자세 태그 4 → HITLAUNCH) — 큐 판정은 다음 프레임 진행률 (0x205BD0 → 0x208E80).</li>
 *   <li>공중 피격 충격의 위 성분 → 수직 속도 (0x1EBA18).</li>
 *   <li>피격 구 = 재질 TakeDamage (0x2040A8).</li>
 * </ul>
 */
class HeavyAttackHitsTest {

	record Victim(int hits, List<Integer> moves, float maxY, float moved) {
	}

	record Run(List<Victim> victims, List<Integer> heroMoves, int heroHitCounter, int heroLog) {
		int hitEnemies() {
			return (int) victims.stream().filter(v -> v.hits > 0).count();
		}
	}

	static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_MIN.WAD")), "gow-rom 없음");
		return rom;
	}

	/** 평평한 바닥 (y = 0) 만 있는 세계 — 공중·착지 판정용. */
	static final EnemyNav.World FLAT = new EnemyNav.World() {
		public float ground(float x, float y, float z, float drop) {
			return y >= 0 && y - drop <= 0 ? 0 : Float.NaN;
		}

		public EnemyNav.WallHit wall(Vector3f o, Vector3f dir, float dist) {
			return null;
		}

		public EnemyNav.Rope rope(Vector3f p, float r) {
			return null;
		}
	};

	/** 영웅 (원점, 정면 −Z, 블레이드 Lv3) 과 at 의 적들 (영웅을 봄, AI 정지, HP 500) — 대상 = targetIdx. */
	static Run run(String wad, float[][] at, int targetIdx, List<ScriptedInput.Step> steps, float secs, boolean bystanders) throws Exception {
		Path rom = rom();
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		WadFile wpn = WadFile.load(Content.file(rom, "R_WPN0_3.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		Animator animator = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(animator);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 3; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), animator, owner);
		c.setHitVolumes(HitVolumes.forHero(heroWad, sk, wpn));
		EnemyFactory.Kind kind = EnemyFactory.load(Content.file(rom, wad), perm, -1);
		List<EnemyFighter> es = new ArrayList<>();
		List<List<Integer>> moves = new ArrayList<>();
		float[] maxY = new float[at.length];
		for (float[] p : at) {
			EnemyFighter e = kind.spawn();
			e.pos.set(p[0], 0, p[1]);
			e.prevPos.set(e.pos);
			e.yaw = (float) Math.atan2(p[0], p[1]); // 영웅 쪽을 봄 (정면 = (−sin, −cos))
			e.holdAi = true;
			e.hp = 500;
			e.setNavWorld(FLAT);
			List<Integer> ml = new ArrayList<>();
			e.combat().onLog(s -> {
				if (s.startsWith("기술 ")) {
					ml.add(Integer.parseInt(s.split(" ")[1]));
				}
			});
			moves.add(ml);
			es.add(e);
		}
		c.setTarget(es.get(targetIdx));
		if (bystanders) {
			c.setBystanders(() -> es);
		}
		List<Integer> hm = new ArrayList<>();
		int[] logged = {0};
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				hm.add(Integer.parseInt(s.split(" ")[1]));
			} else if (s.contains("공격#") && s.contains("피해")) {
				logged[0]++;
			}
		});
		ScriptedInput in = new ScriptedInput(0, steps);
		float dt = 1 / 60f;
		for (float t = 0; t < secs; t += dt) {
			in.advance(dt);
			c.update(in, dt);
			if (c.active()) {
				ctl.updateCombat(c, in, 0, dt, null);
			} else {
				ctl.update(in, 0, dt);
			}
			for (int i = 0; i < es.size(); i++) {
				es.get(i).update(dt);
				maxY[i] = Math.max(maxY[i], es.get(i).pos.y);
			}
		}
		List<Victim> out = new ArrayList<>();
		for (int i = 0; i < es.size(); i++) {
			EnemyFighter e = es.get(i);
			out.add(new Victim(Math.round(500 - e.hp), moves.get(i), maxY[i], (float) Math.hypot(e.pos.x - at[i][0], e.pos.z - at[i][1])));
		}
		return new Run(out, hm, c.hitCounter, logged[0]);
	}

	/** 정면 대상 e0, 왼앞·오른앞, 등 뒤, 왼옆·오른옆. */
	static final float[][] RING = {{0, -40}, {-38, -25}, {38, -25}, {0, 45}, {-45, 5}, {45, 5}};

	static List<ScriptedInput.Step> press(PadButton b) {
		return List.of(new ScriptedInput.Step(0.1f, 0.05f, b));
	}

	/**
	 * L1+□ BLOCK360 (회전 베기, 블레이드 공격 10 개): 둘레 여섯 적이 모두 맞는다 — 예전 엔진은 휘말림 목록이 비어 대상 하나만 맞았다.
	 * 맞은 (상대, 공격) 쌍은 기술당 32 개에서 멈춘다 (0x209AB4). 휘말린 적에게도 영웅 쪽 OnContact (히트 카운터 0x1DB5B8) 가 돈다.
	 */
	@Test
	void spinHitsEveryEnemyAround() throws Exception {
		List<ScriptedInput.Step> spin = List.of(new ScriptedInput.Step(0.05f, 1.2f, PadButton.L1), new ScriptedInput.Step(0.4f, 0.05f, PadButton.SQUARE));
		Run solo = run("R_MIN.WAD", RING, 0, spin, 2.0f, false);
		assertEquals(1, solo.hitEnemies(), "대상만 (휘말림 없음): " + solo);
		Run all = run("R_MIN.WAD", RING, 0, spin, 2.0f, true);
		assertTrue(all.heroMoves().contains(2), "BLOCK360: " + all.heroMoves());
		assertEquals(6, all.hitEnemies(), "둘레 여섯 적: " + all);
		assertEquals(Combatant.HIT_LOG_MAX, all.heroLog(), "기록 32 쌍에서 멈춤: " + all);
		assertEquals(Combatant.HIT_LOG_MAX, all.heroHitCounter(), "휘말린 적도 히트 카운터 (0x1DB5B8): " + all);
		for (int i = 1; i < 6; i++) {
			assertTrue(!all.victims().get(i).moves().isEmpty(), "e" + i + " 피격 반응: " + all);
		}
	}

	/** △ COMBO6A (내려베기 두 번): 정면 대상은 두 번 맞고 둘째에 자세 태그 3 → HITBOUNCE 103, 등 뒤는 안 맞는다. 옆 적은 첫 휘두름에 휘말린다. */
	@Test
	void triangleSlamHitsFrontNotBehind() throws Exception {
		Run r = run("R_MIN.WAD", RING, 0, press(PadButton.TRIANGLE), 1.6f, true);
		assertEquals(32, r.heroMoves().get(0), "△ = COMBO6A: " + r.heroMoves());
		Victim t = r.victims().get(0);
		assertTrue(t.moves().contains(103), "HITBOUNCE: " + t);
		assertTrue(t.maxY() > 40, "튀어 오름: " + t);
		assertEquals(0, r.victims().get(3).hits(), "등 뒤: " + r);
		assertTrue(r.hitEnemies() >= 2, "옆 적 휘말림: " + r);
	}

	/**
	 * △ 홀드 = 띄우기: COMBO6A 의 [0–0.30] "△ 시작부터 홀드" (mode 3) 분기는 창 끝 바로 앞 프레임 (다음 프레임 진행률 ≥ 0.30) 에 큐로 → COMBO7A 0 →
	 * AUTO COMBO4D 29 (부위 11, 자세 태그 4) → 미노타우로스 HITLAUNCH 104. 더 누르고 있으면 COMBO4D [0.30–0.60] 홀드 → COMBOJUMP 10 (따라 뛰어오름).
	 * 예전 엔진은 지금 진행률과 창 끝을 비교해 홀드 분기가 큐에 들어가지 않았다 — △ 홀드가 그냥 △ 였다.
	 */
	@Test
	void holdTriangleLaunches() throws Exception {
		List<ScriptedInput.Step> hold = List.of(new ScriptedInput.Step(0.1f, 1.6f, PadButton.TRIANGLE));
		Run r = run("R_MIN.WAD", RING, 0, hold, 2.6f, true);
		assertTrue(r.heroMoves().containsAll(List.of(32, 0, 29, 10)), "COMBO6A → COMBO7A → COMBO4D → COMBOJUMP: " + r.heroMoves());
		Victim t = r.victims().get(0);
		assertTrue(t.moves().contains(104), "HITLAUNCH: " + t);
		assertTrue(t.maxY() > 60, "띄움 높이: " + t);
		Run tap = run("R_MIN.WAD", RING, 0, press(PadButton.TRIANGLE), 2.0f, true);
		assertTrue(!tap.heroMoves().contains(0) && !tap.heroMoves().contains(29), "짧게 누르면 띄우기 없음: " + tap.heroMoves());
	}

	/** 잡기 (○ GRAB, 공격 플래그 0x1) 는 락온 대상만 — 옆에 겹친 적은 맞지 않는다 (0x209BB8). */
	@Test
	void grabTouchesOnlyTheTarget() throws Exception {
		float[][] two = {{0, -30}, {8, -32}};
		Run r = run("R_SKS.WAD", two, 0, press(PadButton.CIRCLE), 1.2f, true);
		assertTrue(r.heroMoves().contains(200), "GRAB: " + r.heroMoves());
		assertTrue(r.victims().get(1).moves().isEmpty(), "옆 적은 잡기 판정 없음: " + r);
	}

	/**
	 * 공중 피격의 위 성분: 공중 (낙하 중) 적이 AIRH1 (공중 충격 (0, 1000, 150)) 을 맞으면 수직 속도 +160 (÷ 질량 100 × 16) 로 떠 있고,
	 * AIRV1 둘째 (0, −2000, 0) 는 −320 으로 내리꽂는다 (0x1EBA18: +0xE0 += +0xF0, 그 뒤 +0xF0.y = 0). 예전 엔진은 위 성분을 버려 공중 콤보 중 적이
	 * 중력대로만 떨어졌다.
	 */
	@Test
	void airImpulseLiftsAndSlams() throws Exception {
		Path rom = rom();
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		CombatData hero = new CombatData(perm, CombatData.heroFiles(), AnimationSet.load(heroWad, "ANM_hero"));
		CombatFile.Attack lift = hero.move(13).attacks().get(0);   // AIRH1
		CombatFile.Attack slam = hero.move(11).attacks().get(1);   // AIRV1 둘째
		assertEquals(1000, lift.impulse()[4], 0.5f);
		assertEquals(-2000, slam.impulse()[4], 0.5f);
		float none = airDrop(rom, perm, null), up = airDrop(rom, perm, lift), down = airDrop(rom, perm, slam);
		assertTrue(up > none + 15, "떠 있음: 충격 없음 " + none + " / 위 " + up);
		assertTrue(down < none - 15, "내리꽂힘: " + none + " / 아래 " + down);
	}

	/** 높이 150 에서 떨어지는 미노타우로스를 (공격 a 의 공중 충격으로) 밀고 0.3 초 뒤 높이. */
	private static float airDrop(Path rom, WadFile perm, CombatFile.Attack a) throws Exception {
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, "R_MIN.WAD"), perm, -1);
		EnemyFighter e = k.spawn();
		e.holdAi = true;
		e.setNavWorld(FLAT);
		e.update(1 / 60f);
		e.pos.y = 150;
		e.prevPos.set(e.pos);
		for (int i = 0; i < 3; i++) {
			e.update(1 / 60f);
		}
		assertEquals(EnemyNav.Mode.AIR, e.nav().mode(), "공중");
		if (a != null) {
			EnemyFighter hitter = k.spawn();
			hitter.pos.set(0, e.pos.y, 40);
			e.combat().push(hitter.combat(), a, 3);
		}
		for (int i = 0; i < 18; i++) {
			e.update(1 / 60f);
		}
		return e.pos.y;
	}

	/**
	 * 피격 구 = 재질 bitfield1 비트 0 TakeDamage (0x2040A8). 몸만이 아니라 키클롭스 다리·머리, 켄타우로스 팔·다리에도 맞는다.
	 * 미노타우로스 도끼손 (부위 2) 은 피격 구가 아니다.
	 */
	@Test
	void takeDamageSpheres() throws Exception {
		Path rom = rom();
		assertEquals(Set.of(0, 1, 6, 7), hurtParts(rom, "R_CY3.WAD"), "키클롭스: 머리 0 · 몸 1 · 다리 6/7");
		assertEquals(Set.of(1), hurtParts(rom, "R_MIN.WAD"), "미노타우로스: 몸만");
		assertEquals(Set.of(1, 4, 6, 7), hurtParts(rom, "R_CNM.WAD"), "켄타우로스: 몸 · 왼팔 · 다리");
	}

	private static Set<Integer> hurtParts(Path rom, String wad) throws Exception {
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		EnemyFighter e = EnemyFactory.load(Content.file(rom, wad), perm, -1).spawn();
		return new TreeSet<>(e.hitVolumes().hurtParts());
	}

	/**
	 * □□△ "Plume of Prometheus" (COMBO7A 3) 충격파 템플릿 (SCR_ConcussionBurst idx 9 · 10): Context 10, 종류 −1, 지상 Away 250, 방어 Away 500,
	 * 막을 수 있음 · 석화 아님 (Concussion_Template 0x190388 의 키 → 오프셋). 임페일 1 (13) 은 아래로 −3000.
	 */
	@Test
	void concussionTemplateFields() throws Exception {
		Path rom = rom();
		devple.gow.engine.game.Tweaks tw = new devple.gow.engine.game.Tweaks();
		tw.load(WadFile.load(Content.file(rom, Region.of(rom).perm())));
		var c = devple.gow.engine.fx.MagicFx.concussionSpec(tw, "/TweakTemplates/Concussion/009/");
		assertEquals(10, c.tag());
		assertEquals(-1, c.cls());
		assertEquals(250f, c.impulse()[2], 0.01f, "Ground Impulse Away");
		assertEquals(500f, c.impulse()[8], 0.01f, "방어 밀기 #d0d4210c");
		assertTrue(!c.unblockable() && !c.freeze());
		assertEquals(-3000f, devple.gow.engine.fx.MagicFx.concussionSpec(tw, "/TweakTemplates/Concussion/013/").impulse()[1], 0.01f);
	}

	/**
	 * 충격파 피격 (0x1909C8 → 0x204690): 반경 안 미노타우로스는 경직 검사 없이 피격 이벤트 13 (자세 태그 = Context 10 → A110 HITBOUNCE) 을 받고
	 * 중심에서 밀려난다. 예전 엔진은 HP 만 깎았다.
	 */
	@Test
	void concussionStaggersAndPushes() throws Exception {
		Path rom = rom();
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		EnemyFighter e = EnemyFactory.load(Content.file(rom, "R_MIN.WAD"), perm, -1).spawn();
		e.holdAi = true;
		e.setNavWorld(FLAT);
		e.pos.set(0, 0, -30);
		e.prevPos.set(e.pos);
		e.hp = 100;
		List<Integer> ml = new ArrayList<>();
		e.combat().onLog(s -> {
			if (s.startsWith("기술 ")) {
				ml.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		e.update(1 / 60f);
		float[] imp = {0, 0, 250, 0, 0, 250, 0, 0, 500};
		assertEquals(Hittable.Result.HIT, e.concussionHit(null, new Vector3f(0, 0, 0), 6, 10, -1, imp, false));
		assertEquals(94, e.hp, 0.01f);
		for (int i = 0; i < 20; i++) {
			e.update(1 / 60f);
		}
		assertTrue(ml.contains(110), "자세 태그 10 → HITBOUNCE 110: " + ml);
		assertTrue(e.pos.z < -33, "중심에서 밀려남: " + e.pos);
	}
}
