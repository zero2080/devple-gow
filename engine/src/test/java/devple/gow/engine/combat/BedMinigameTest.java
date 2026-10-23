package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.boss.AutoPad;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * ATHN01F 선장실 침대 미니게임 (37 문서 §11.8): 쌍둥이 (AI1 305 aoxpt) 가 M69 대기 (SET_1B0 305, 원 볼륨 진입 1019 69) 일 때 영웅 ○ GRAB (M200)
 * → HIT_CONFIRM tgt=305 tag=305 → 영웅 846 / 쌍둥이 EV_HIT_REACT tag=200 → 846. 846–851 의 SCR_TimedPress (템플릿 300–305, 창 [0.31–0.57])
 * 를 모두 맞히면 853 MAIXPTBEDMOUNTWIN (ORB_EMIT 850 ×5) → 875 (둘째 판 대기, 태그 306), 놓치면 852 → 69.
 */
class BedMinigameTest {

	private static final float DT = 1 / 60f;

	private record Run(List<Integer> heroMoves, List<Integer> twinMoves, List<Integer> orbs, EnemyFighter twins) {
	}

	private static Run run(boolean answer) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "ATHN01F.WAD")), "gow-rom 없음");
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(an);
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
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		Combatant c = new Combatant(cd, an, owner);
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, "ATHN01F.WAD"), perm, 305, "aoxpt");
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		EnemyFighter.resetSpawnCount();
		EnemyFighter en = k.spawn(305);
		en.pos.set(0, 0, -50);
		en.setFoe(new Hittable() {
			public Vector3f position() { return ctl.position; }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public int stanceTag() { return c.stanceTag(); }
			public boolean invulnerable() { return true; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
		});
		c.setTarget(en);
		List<Integer> hm = new ArrayList<>(), em = new ArrayList<>(), orbs = new ArrayList<>();
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				hm.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		en.combat().onLog(s -> {
			if (s.startsWith("기술 ")) {
				em.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		en.combat().addEventListener(ev -> {
			if (ev.type() == 2) { // ORB_EMIT
				orbs.add(ev.index());
			}
		});
		assertEquals(32767f, en.maxHp(), "부술 수 없는 소품");
		// 원 볼륨 진입: twinsCircle-pCube2-circleON → Minigamecircle-miniGame1idle69 (1019 69, 인자2 1)
		assertTrue(en.combat().forceMove(69));
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.3f, 0.05f, PadButton.CIRCLE)));
		GrabSync sync = new GrabSync(ctl, c);
		AutoPad pad = new AutoPad();
		for (float t = 0; t < 30f; t += DT) {
			in.advance(DT);
			if (!sync.active()) {
				c.update(in, DT);
				if (c.active()) {
					ctl.updateCombat(c, in, 0, DT, en.pos);
				} else {
					ctl.update(in, 0, DT);
				}
			}
			en.update(DT);
			Input gi = in;
			if (answer && sync.active()) {
				pad.drive(sync.timed(), sync.qte()); // GameScene AUTO 와 같은 자동 입력
				gi = pad;
			}
			sync.update(gi, en, k.tweaks(), DT);
			if (em.contains(875) || em.lastIndexOf(69) > 0) {
				en.update(DT); // 새 기술의 첫 이벤트 (SET_1B0) 적용
				break;
			}
		}
		return new Run(hm, em, orbs, en);
	}

	/** ○ 로 올라타 6 프롬프트를 모두 맞힘 → 853 → 875, 오브 이미터 850 다섯 번, 쌍둥이는 그대로. */
	@Test
	void circleGrabStartsBedMinigameAndWinAwardsOrbs() throws Exception {
		Run r = run(true);
		assertTrue(r.heroMoves().contains(200), "○ GRAB: " + r.heroMoves());
		assertTrue(r.heroMoves().contains(846), "영웅 MAIXPTBEDMOUNT: " + r.heroMoves());
		assertEquals(List.of(69, 846, 847, 848, 849, 850, 851, 853, 875), r.twinMoves(), "쌍둥이 기술 순서");
		assertEquals(List.of(850, 850, 850, 850, 850), r.orbs(), "853 ORB_EMIT 850 ×5");
		assertFalse(r.twins().dead(), "미니게임 성공은 처형이 아니다");
		assertEquals(306, r.twins().combat().stanceTag(), "875 = 둘째 판 대기 (SET_1B0 306 → 영웅 GRAB → 880)");
	}

	/** 프롬프트를 놓치면 그 창의 REQUEST0 → 852 (실패) → AUTO 69 (다시 대기). */
	@Test
	void missedPromptFailsBackToIdle69() throws Exception {
		Run r = run(false);
		assertEquals(List.of(69, 846, 852, 69), r.twinMoves(), "실패 경로");
		assertTrue(r.orbs().isEmpty());
	}

	/** 스크립트 1019 인자2 ≠ 0 + 없는 기술 (A70 "REQUEST0 -> -", quitMime −1): 지금 기술만 끊고 자세 태그도 지움. */
	@Test
	void stopClearsMoveAndStanceTag() throws Exception {
		Run r = run(false);
		Combatant tc = r.twins().combat();
		tc.forceMove(69);
		r.twins().update(DT);
		assertEquals(305, tc.stanceTag());
		assertFalse(tc.request(70), "A70 은 기술이 없다");
		tc.stop();
		assertFalse(tc.active());
		assertEquals(-1, tc.stanceTag());
	}
}
