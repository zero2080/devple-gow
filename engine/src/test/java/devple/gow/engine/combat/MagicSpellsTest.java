package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.GameRules;
import devple.gow.engine.game.PlayerState;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 메두사·제우스·하데스 (35 문서): 영웅 기술 SCRIPT 이벤트 → 효과, MP 소모 데이터 그대로. */
class MagicSpellsTest {

	private static final float DT = 1 / 60f;

	/** 헤드리스 영웅 1벌. */
	private static final class Rig {
		final Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		final WadFile perm;
		final Tweaks tw = new Tweaks();
		final PlayerState ps;
		final Magic magic;
		final AnimationSet set;
		final CombatData cd;
		final HeroController ctl;
		final Combatant c;
		final List<Integer> moves = new ArrayList<>();
		int selected;

		Rig(int magicId) throws Exception {
			Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_HERO0.WAD")), "gow-rom 없음");
			WadFile hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
			perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
			tw.load(perm);
			tw.load(hero);
			ps = new PlayerState(GameRules.load(perm));
			ps.addItem(magicId, 1);
			selected = magicId;
			magic = new Magic(tw, ps);
			Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
			set = AnimationSet.load(hero, "ANM_hero");
			Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
			ctl = new HeroController(an);
			CombatOwner owner = new CombatOwner() {
				public float hp() { return 100; }
				public boolean onGround() { return true; }
				public int bladeLevel() { return 1; }
				public boolean godMode() { return false; }
				public boolean artemisEquipped() { return false; }
				public int selectedMagic() { return selected; }
				public int classId() { return 0; }
				public boolean hasMagic(int id) { return magic.has(id); }
				public void useMagic(int id) { magic.use(id); }
				public Vector3f position() { return ctl.position; }
				public float yaw() { return ctl.yaw; }
			};
			cd = new CombatData(perm, CombatData.heroFiles(), set);
			c = new Combatant(cd, an, owner);
			c.onLog(s -> {
				if (s.startsWith("기술 ")) {
					moves.add(Integer.parseInt(s.split(" ")[1]));
				}
			});
		}

		void tick(ScriptedInput in, Vector3f aim) {
			in.advance(DT);
			c.update(in, DT);
			if (c.active()) {
				ctl.updateCombat(c, in, 0, DT, aim);
			} else {
				ctl.update(in, 0, DT);
			}
			magic.update(c, in, DT);
		}
	}

	@Test
	void medusaPetrifiesMinotaurAndShatters() throws Exception {
		Rig r = new Rig(4);
		EnemyFactory.Kind k = EnemyFactory.load(r.rom.resolve("R_MIN.WAD"), r.perm, -1);
		EnemyFighter en = k.spawn();
		en.pos.set(0, 0, -150); // 정면 150 유닛 ≈ 9.4m (Distance 15m 안)
		EnemyFighter side = k.spawn();
		side.pos.set(200, 0, 40); // 원뿔 밖 (옆/뒤)
		assertEquals(6000f, en.freezeMax(), "Sold+0x26C 미노타우로스 저항");
		r.c.setTarget(en);
		r.magic.setTargets(() -> List.of(en, side));
		boolean[] shatterCb = {false};
		en.onShatter(() -> shatterCb[0] = true);
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 3f, PadButton.L2)));
		float stoneAt = -1, mpBefore = r.ps.mp;
		for (float t = 0; t < 3.5f; t += DT) {
			r.tick(in, en.pos);
			en.update(DT);
			side.update(DT);
			if (stoneAt < 0 && en.petrified()) {
				stoneAt = t;
			}
		}
		assertTrue(r.moves.contains(81) && r.moves.contains(82), "PSENTHEAD→USEHEAD: " + r.moves);
		assertTrue(stoneAt > 0, "석화 안 됨, 게이지 " + en.freezeMeter() + " moves " + r.moves);
		assertFalse(side.petrified(), "원뿔 밖 적은 그대로");
		// 단계 0: Freeze Factor 100 × 60 = 6000/s → 저항 6000 은 ≈1초 광선
		float used = mpBefore - r.ps.mp;
		assertTrue(used > 10f && used < 12.5f * 3.1f, "초당 12.5 소모: " + used);
		assertTrue(en.petrified(), "돌 지속 5초 (AI1 +0x40) 동안 유지");
		assertEquals(5f, en.stoneTime(), 0.01f);
		// 돌 상태에서 공격 → 부서짐 (Stone Kill)
		ScriptedInput atk = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 0.05f, PadButton.SQUARE),
				new ScriptedInput.Step(0.5f, 0.05f, PadButton.SQUARE)));
		r.ctl.position.set(0, 0, -100);
		for (float t = 0; t < 1.5f && !en.shattered(); t += DT) {
			r.tick(atk, en.pos);
			en.update(DT);
		}
		assertTrue(en.shattered(), "돌 적중 → 파괴: moves " + r.moves);
		assertTrue(shatterCb[0]);
		assertTrue(en.dead());
	}

	/** 단계 2: 광선 중 ○ → HEADNUKESTART(64) → HEADNUKESLAM(65/66) 의 SCR_MedusaNuke → 반경 안(원뿔 밖 포함) 적 석화, Nuke Points 소모. */
	@Test
	void medusaNukeAtStage2() throws Exception {
		Rig r = new Rig(4);
		r.ps.level.put(devple.gow.engine.game.Weapon.MEDUSA, 2);
		r.ps.mpLevel = 3; // Nuke Points 100 이상 (MP 최대가 레벨 0 에서는 부족)
		r.ps.mp = r.ps.maxMp();
		assertTrue(r.ps.mp >= 100, "최대 MP " + r.ps.mp);
		EnemyFighter behind = EnemyFactory.load(r.rom.resolve("R_MIN.WAD"), r.perm, -1).spawn();
		behind.pos.set(0, 0, 120); // 뒤쪽 (광선 원뿔 밖), Nuke L 반경 15 × 16 = 240 안
		r.magic.setTargets(() -> List.of(behind));
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 3.5f, PadButton.L2),
				new ScriptedInput.Step(1.5f, 0.05f, PadButton.CIRCLE)));
		float mpBefore = r.ps.mp;
		for (float t = 0; t < 3.5f && !behind.petrified(); t += DT) {
			r.tick(in, new Vector3f(0, 0, -150));
			behind.update(DT);
		}
		assertTrue(r.moves.contains(64), "광선 중 ○ → HEADNUKESTART: " + r.moves);
		assertTrue(r.moves.contains(65) || r.moves.contains(66), "HEADNUKESLAM: " + r.moves);
		assertTrue(behind.petrified(), "Nuke 동결 30000 ≥ 미노타우로스 6000: 게이지 " + behind.freezeMeter());
		assertTrue(mpBefore - r.ps.mp >= 100 - 1e-3, "단계 2 Nuke Points 100 소모: " + (mpBefore - r.ps.mp));
	}

	/** 단계 0: Allow Nuke = 0 → +0x1AC&0x1000 이 꺼져 광선 중 ○ 분기(flags 0x8) 불가 (SCR_TriggerMedusaHead 0x197FD8). */
	@Test
	void medusaNukeNotAtStage0() throws Exception {
		Rig r = new Rig(4);
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 2f, PadButton.L2),
				new ScriptedInput.Step(1.5f, 0.05f, PadButton.CIRCLE)));
		for (float t = 0; t < 2f; t += DT) {
			r.tick(in, new Vector3f(0, 0, -150));
		}
		assertTrue(r.moves.contains(82), "USEHEAD: " + r.moves);
		assertFalse(r.moves.contains(64), "단계 0 은 Nuke 불가: " + r.moves);
	}

	/** 분기 flags 0x800 = 차지 완료(+0x1AC&amp;0x400): HITLIE 의 CHARGE_CHECK(a=2.5) 뒤에야 AUTO → HITGETUPGROUND(194) (10 문서 §2.4). */
	@Test
	void chargeCheckGatesGetUp() throws Exception {
		Rig r = new Rig(4);
		ScriptedInput none = new ScriptedInput(0, List.of());
		r.tick(none, null);
		r.c.forceMove(118);
		float upAt = -1;
		for (float t = 0; t < 6f && upAt < 0; t += DT) {
			r.tick(none, null);
			if (r.moves.contains(194)) {
				upAt = t;
			}
		}
		assertTrue(upAt >= 2.4f && upAt < 5f, "2.5초 누워 있다가 일어남: " + upAt + " " + r.moves);
	}

	@Test
	void medusaStoneWearsOff() throws Exception {
		Rig r = new Rig(4);
		EnemyFighter en = EnemyFactory.load(r.rom.resolve("R_MIN.WAD"), r.perm, -1).spawn();
		assertTrue(en.addFreeze(7000f));
		assertTrue(en.petrified());
		for (float t = 0; t < 5.2f; t += DT) {
			en.update(DT);
		}
		assertFalse(en.petrified(), "5초 후 해제");
		assertEquals(en.freezeMax(), en.freezeMeter());
	}

	@Test
	void zeusBoltHitsAtDistanceAndCostsMp() throws Exception {
		Rig r = new Rig(5);
		TrainingDummy far = new TrainingDummy(new Vector3f(0, 0, -400), 500, 1000); // 25m
		r.c.setTarget(far);
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 0.9f, PadButton.L2)));
		int maxBolts = 0;
		for (float t = 0; t < 3f; t += DT) {
			r.tick(in, far.pos);
			far.update(DT);
			maxBolts = Math.max(maxBolts, r.magic.zeus.bolts().size());
		}
		assertTrue(r.moves.contains(60) && r.moves.contains(61), "BOLTPULLOUT→BOLTTHROW: " + r.moves);
		assertTrue(maxBolts >= 1, "투사체 생성");
		float lost = 500 - far.hp;
		assertTrue(lost >= 8 && lost % 8 == 0, "Arrow 002 Damage 8 × 발수: " + lost);
		int throwsN = (int) (lost / 8);
		assertEquals(100f - 5f * throwsN, r.ps.mp, 0.01f, "Stage 0 Magic Drain 5 / 발");
		assertEquals(0, r.magic.zeus.lastLevel);
	}

	@Test
	void zeusChargedShotUsesCh2() throws Exception {
		Rig r = new Rig(5);
		r.ps.level.put(devple.gow.engine.game.Weapon.ZEUS, 2);
		TrainingDummy far = new TrainingDummy(new Vector3f(0, 0, -300), 500, 1000);
		r.c.setTarget(far);
		// R2 홀드 + □ 홀드(차지): Stage 2 Ch2 Threshold 0.75 × Charge Time 0.75 = 0.56초
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 0.8f, PadButton.L2),
				new ScriptedInput.Step(0.1f, 0.8f, PadButton.SQUARE)));
		for (float t = 0; t < 2f; t += DT) {
			r.tick(in, far.pos);
			far.update(DT);
		}
		assertEquals(2, r.magic.zeus.lastLevel, "Ch2: moves " + r.moves);
		assertEquals(20f, r.magic.zeus.lastDrain, 0.01f, "Stage 2 Ch2 Magic Drain");
		assertTrue(far.hp <= 500 - 65, "Arrow 004 Damage 65: " + far.hp);
	}

	@Test
	void hadesSoulsDamageNearbyEnemies() throws Exception {
		Rig r = new Rig(6);
		assertEquals(75f, r.magic.cost(6), 0.01f);
		TrainingDummy a = new TrainingDummy(new Vector3f(150, 0, -100), 500, 1000);
		TrainingDummy b = new TrainingDummy(new Vector3f(-150, 0, -100), 500, 1000);
		TrainingDummy far = new TrainingDummy(new Vector3f(0, 0, -2000), 500, 1000); // Reach 25m=400 밖
		r.c.setTarget(a);
		r.magic.setTargets(() -> List.of(a, b, far));
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 0.1f, PadButton.L2)));
		int maxSouls = 0;
		for (float t = 0; t < 6f; t += DT) {
			r.tick(in, a.pos);
			a.update(DT);
			b.update(DT);
			maxSouls = Math.max(maxSouls, r.magic.hades.souls().size());
		}
		assertTrue(r.moves.contains(508), "RAISETHEDEAD: " + r.moves);
		assertEquals(3, maxSouls, "Stage 0 Num Dead Guys");
		assertEquals(25f, r.ps.mp, 0.01f, "3 × 25 = 75");
		assertTrue(a.hp < 500 && b.hp < 500, "두 적 모두 피해: " + a.hp + ", " + b.hp);
		assertEquals(0f, (500 - a.hp) % 10f, 0.01f, "Damage Per Hit 10");
		assertEquals(500f, far.hp, "사거리 밖");
	}
}
