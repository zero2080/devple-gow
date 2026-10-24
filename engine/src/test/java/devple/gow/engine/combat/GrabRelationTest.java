package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 잡기 처형의 시작 관계 (37 문서 §11.5): 처형 기술의 이벤트 19 (FLAG_4) 창이 +0x1AC |= 0x4 → 0x2441E0(+0x1AC, 0x24) → 0x204BF0 으로
 * 동기 상대를 설계 관계 (처형 0 프레임 synchJoint 일치) 로 끌어 붙인다 — 어디서 잡든 0.5 초 뒤 오차 &lt; 1.
 * 끌기 전 GRAB 첫 접촉 자리도 대부분 설계 관계와 같다: GRAB 의 extra1 구 (부위 9, [0.27–0.32]) 가 닿는 거리 (멀리서 누르면 달려들어 이 창에서 잡는다).
 */
class GrabRelationTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_MIN.WAD")), "gow-rom 없음");
		return rom;
	}

	/** 같은 데이터로 매번 새 영웅·적을 만드는 공급자. */
	private static Supplier<GrabRelation.Trial> trials(String wad) throws Exception {
		Path rom = rom();
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		WadFile wpn = WadFile.load(Content.file(rom, "R_WPN0_1.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, wad), perm, -1);
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		return () -> {
			EnemyFighter.resetSpawnCount();
			return GrabRelation.Trial.create(sk, set, tw, cd, () -> HitVolumes.forHero(heroWad, sk, wpn), k);
		};
	}

	private record Case(String wad, int finisher, float design, boolean extra1) {
	}

	/**
	 * 적 종류별 시작 거리: 설계 관계 거리 (±1), 처형 기술, 찾은 자리에서 결합 순간 (끌기 전) 오차 &lt; 2, 결합 거리 ≈ 설계 (±2), 0.5 초 뒤 오차 &lt; 1.
	 * extra1 = 적중이 GRAB 부위 9 창 [0.27–0.32] 에서 (멀리서 달려들어 잡음).
	 * <p>설계 관계는 처형 0 프레임의 zeroJoint 기준 synchJoint (0x1F7110 → 0x204BF0) — 원작 배치 (05 §2.8.1) 의 디스크립터 1 (관절 이동) 이 들어간 자세다.
	 * 고르곤 R_MEW/R_ME2 · 세이렌 · 케르베로스 · 하피 세트는 데이터 타입이 [0(p2 3)] / [0, 10 …] 이라 예전 항목 번호 배치는 이동을 하나도 읽지 않았고
	 * (zero/synch 가 바인드 자리), 설계 거리가 고르곤 37.7 · 세이렌 45.9 · 새끼 케르베로스 42.2 · 케르베로스 43.3 · 하피 7.9 였다. 원작 배치로는
	 * 26.4 · 21.7 · 34.8 · 44.2 · 23.9 이고, 열 종류 모두 GRAB 첫 접촉 (몸 구가 처음 닿는 거리 — 설계 관계와 독립인 CDV 판정) 이 설계 관계와 0.7 안에서
	 * 맞는다 (예전: 고르곤은 부위 9 창에서만 맞춰졌고 하피는 캡슐 밀기로 13 밖) [확실: 코드 경로 + 데이터].
	 */
	@Test
	void designedStartPerEnemy() throws Exception {
		List<Case> cases = List.of(
				new Case("R_MIN.WAD", 400, 70.4f, true),   // 미노타우로스 MAIMIN01
				new Case("R_MEW.WAD", 260, 26.4f, false),  // 고르곤 MAIMEWGRAB
				new Case("R_ME2.WAD", 263, 26.4f, false),  // 고르곤 2 MAIMEWGRAB
				new Case("R_SIR.WAD", 421, 21.7f, false),  // 세이렌 MAISIRKILL (HP 5)
				new Case("R_SAT.WAD", 431, 37.4f, true),   // 사티로스 MAISATGRAB
				new Case("R_SKS.WAD", 220, 27.6f, false),  // 해골병 (레기오네어) MAISKB00
				new Case("R_CNM.WAD", 291, 40.6f, true),   // 켄타우로스 MAICNMKILL
				new Case("R_CEP.WAD", 286, 34.8f, false),  // 케르베로스 새끼 MAICEPSTOMP
				new Case("R_CEA.WAD", 395, 44.2f, false),  // 케르베로스 MAICEAKILL
				new Case("R_HAR.WAD", 270, 23.9f, false)); // 하피 MAIHARWINGRIP (뒤에서, 요 차 0)
		StringBuilder log = new StringBuilder();
		for (Case c : cases) {
			Assumptions.assumeTrue(Files.exists(rom().resolve(c.wad())));
			GrabRelation.Result r = GrabRelation.search(trials(c.wad()), 10, 130);
			assertNotNull(r, c.wad());
			log.append(String.format("%s %s%n", c.wad(), r));
			assertEquals(c.finisher(), r.finisher(), c.wad());
			assertEquals(c.design(), r.design(), 1f, c.wad() + " 설계 관계");
			assertTrue(r.contact() < 2f, c.wad() + " 결합 순간 오차: " + r);
			assertTrue(r.error() < 1f, c.wad() + " 0.5 초 뒤 오차: " + r);
			assertEquals(r.design(), r.link(), 2f, c.wad() + " 결합 거리 ≈ 설계 관계: " + r);
			assertTrue(r.start() > r.link(), c.wad() + " GRAB 루트 모션으로 달려들어 잡음: " + r);
			if (c.extra1()) {
				assertTrue(r.hitProgress() >= 0.27f && r.hitProgress() <= 0.33f, c.wad() + " 부위 9 창에서 적중: " + r);
			}
		}
		System.out.print(log);
	}

	/**
	 * 너무 가까이서 누르면 바로 닿아 결합 순간 관계가 그만큼 어긋나지만 (오차 ≈ 거리 차이), 처형 기술의 이벤트 19 창 (미노타우로스 400 MAIMIN01)
	 * 이 0x204BF0 으로 끌어 0.5 초 뒤엔 설계 관계가 된다.
	 */
	@Test
	void tooCloseIsPulledIntoRelation() throws Exception {
		Supplier<GrabRelation.Trial> t = trials("R_MIN.WAD");
		GrabRelation.Result best = GrabRelation.search(t, 10, 130);
		GrabRelation.Result close = GrabRelation.run(t.get(), 40);
		assertTrue(close.hitProgress() < 0.12f, "가까우면 GRAB 첫 판정 (부위 1 [0.05–]) 에서 바로 잡힘: " + close);
		assertTrue(close.contact() > 20 && close.contact() > best.contact() + 20, "결합 순간은 잡은 자리: " + close + " / " + best);
		assertEquals(close.design() - close.link(), close.contact(), 6f, "결합 순간 오차 ≈ 설계 관계 − 결합 거리");
		assertTrue(close.error() < 1f, "이벤트 19 끌기로 설계 관계: " + close);
	}

	/**
	 * 몸 캡슐 (Sold +0x234) 이 설계 거리보다 큰 처형 (37 문서 §11.5): 사이클롭스 (캡슐 합 57.6 &gt; 설계 29.7).
	 * <ul>
	 *   <li>원작 밀기 규칙 [0x1E2DD8 / 0x1E28B0 — GRAB stateMask 7 = 단단함] 으로는 GRAB 이 캡슐 경계에서 닿는다 (결합 ≥ 40).</li>
	 *   <li>그 어긋남은 이벤트 19 (FLAG_4) 창이 메운다 [확실: 0x206B44 → +0x1AC |= 0x4, 0x205B28 의 0x24 검사 → 0x204BF0]: 적 240
	 *       MAICYWMINI02 [0–0.86] 이 영웅을 끈다 → 0.5 초 뒤 오차 &lt; 1 (예전: 27.3).</li>
	 *   <li>밀기를 빼면 (비교용) GRAB 첫 접촉이 그대로 설계 관계.</li>
	 * </ul>
	 * 하피는 원작 배치 (05 §2.8.1) 로 설계 거리가 23.9 (캡슐 합 19.6 보다 큼) 라 캡슐에 막히지 않는다 — 예전 항목 번호 배치의 7.9 (zero/synch 바인드) 가
	 * 만든 경우였다 ({@link #designedStartPerEnemy}).
	 */
	@Test
	void capsuleLimitedFinishers() throws Exception {
		String w = "R_CY2.WAD";
		Assumptions.assumeTrue(Files.exists(rom().resolve(w)));
		Supplier<GrabRelation.Trial> t = trials(w);
		GrabRelation.Result free = GrabRelation.search(t, 5, 130, false);
		assertNotNull(free, w);
		assertTrue(free.contact() < 1f, w + " 밀기 없으면 첫 접촉 = 설계 관계: " + free);
		// 서 있는 사이클롭스 (잡기 원 상태, 변위 0) 는 자기를 밀지 않는다 (0x1EBB38, 32 문서) → 영웅이 캡슐 안 깊이 (수평 < 45.6) 에서 시작하는 모의는
		// 원작에 생길 수 없다 (영웅은 걸어 들어오며 늘 자기를 밀어 경계 밖에 있음 — 플레이어는 정지 판정 없음)
		GrabRelation.Result pushed = GrabRelation.search(t, 50, 130, true);
		assertTrue(pushed.link() > free.link() + 5, w + " 원작 밀기로 첫 접촉은 설계 거리 밖: " + pushed + " / " + free);
		assertTrue(pushed.contact() > 5f, w + ": " + pushed);
		assertEquals(0f, pushed.error(), 1f, w + " 이벤트 19 끌기로 설계 관계 (±1): " + pushed);
		for (float d : new float[]{40, 60, 80}) {
			GrabRelation.Result r = GrabRelation.run(t.get(), d, true);
			assertEquals(0f, r.error(), 1f, w + " 시작 " + d + ": " + r);
		}
		GrabRelation.Trial probe = t.get();
		// 캡슐 경계 (영웅 쪽 수평 45.6) 근처 — 밀기 후보는 질의 구에 닿는 사이클롭스 CDV 구가 있을 때만이라 (0x175648) 자세에 따라 조금 안쪽 (≈ 43)
		assertTrue(pushed.link() >= 40f, "사이클롭스 캡슐 경계 근처: " + pushed);
		CombatFile.Move m = probe.enemy().combat().data().move(240);
		assertNotNull(SyncPull.linkedPullWindow(m, 0.5f), "적 240 이벤트 19 [0–0.86]");
		assertNull(SyncPull.linkedPullWindow(m, 0.9f));
		assertNull(SyncPull.pullWindow(m, 0.5f), "이벤트 22 는 없음");
	}

	/**
	 * 적 수직 루트 모션 (0x1EBD78 → stateMask &amp; 0x1C == 0 기술은 0x1ECD60 경로: 중력 없는 3D 변위, 바닥 아래만 막음): zeroJoint 가 위로 가는
	 * 자유 이동 기술은 그만큼 오른다 (예전: 수직 버림). stateMask 7 기술은 지상 이동이 붙이므로 그대로.
	 */
	@Test
	void enemyVerticalRootMotionInFreeMoves() throws Exception {
		boolean tested = false;
		for (String w : new String[]{"R_SAT.WAD", "R_CY2.WAD", "R_HAR.WAD", "R_MIN.WAD", "R_SKS.WAD"}) {
			Supplier<GrabRelation.Trial> t = trials(w);
			GrabRelation.Trial probe = t.get();
			EnemyFighter en = probe.enemy();
			Skeleton sk = en.animator().skeleton();
			int zj = sk.indexOf("zeroJoint");
			int best = -1;
			float bestRise = 0;
			for (int id = 0; id < 1000; id++) {
				CombatFile.Move m = en.combat().data().move(id);
				if (m == null || (m.stateMask() & 0x1C) != 0 || !en.combat().forceMove(id) || en.animator().current() == null) {
					continue;
				}
				var a = en.animator().current();
				float y0 = a.sample(sk, 0, false).pos[zj][1], peak = y0;
				for (float s = 0; s <= a.duration; s += 1 / 30f) {
					peak = Math.max(peak, a.sample(sk, s, false).pos[zj][1]);
				}
				if (peak - y0 > bestRise) {
					bestRise = peak - y0;
					best = id;
				}
			}
			if (best < 0 || bestRise < 15) {
				continue;
			}
			GrabRelation.Trial tr = t.get();
			EnemyFighter e = tr.enemy();
			e.pos.set(0, 0, -800);
			e.combat().forceMove(best);
			int serial = e.combat().moveSerial();
			var a = e.animator().current();
			float y0 = a.sample(sk, 0, false).pos[zj][1];
			float peakY = 0, worst = 0;
			for (int i = 0; i < 600 && e.combat().active() && e.combat().moveSerial() == serial; i++) {
				e.update(1 / 60f);
				if (e.combat().moveSerial() != serial) {
					break;
				}
				float tm = Math.min(e.animator().time(), a.duration);
				float want = e.scale * (a.sample(sk, tm, false).pos[zj][1] - y0);
				worst = Math.max(worst, Math.abs(e.pos.y - want));
				peakY = Math.max(peakY, e.pos.y);
			}
			assertTrue(peakY > 10, w + " 기술 " + best + " 이 오름: " + peakY);
			// 첫 프레임은 직전 델타 유지 (0x1F7110 카운터 < 2) → 한 프레임 늦음
			assertTrue(worst < 6f, w + " 기술 " + best + ": 높이 = 배율 × (zero y − 처음), 최대 차이 " + worst);
			tested = true;
			break;
		}
		Assumptions.assumeTrue(tested, "zeroJoint 가 오르는 자유 이동 기술 없음");
	}

	/**
	 * 결합 없는 끌기의 끄는 쪽 = 공격 대상이 영웅인 적 (0x2097A8: 플래그 0x20 기술 시작 때 +0x84 = +0x80) — 가장 가까운 적이 아니다.
	 * 아기 히드라 두 마리: 가까운 쪽은 대기, 먼 쪽이 825 BHABABYGRAB [0–0.22] → 먼 쪽이 영웅을 끈다.
	 */
	@Test
	void lonePullUsesAttackTargetNotNearest() throws Exception {
		Path rom = rom();
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "ATHN01C.WAD")));
		WadFile heroWad = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, "ATHN01C.WAD"), perm, 252, "hydra enc2");
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		GrabRelation.Trial tr = GrabRelation.Trial.create(sk, set, tw, cd, null, k);
		EnemyFighter near = tr.enemy();
		EnemyFighter far = k.spawn(252);
		far.holdAi = true;
		far.setFoe(new Hittable() {
			public Vector3f position() { return tr.ctl().position; }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public int stanceTag() { return tr.hero().stanceTag(); }
			public boolean invulnerable() { return true; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
		});
		near.pos.set(0, 0, -40);
		far.pos.set(0, 0, 120);
		far.yaw = 0;
		assertTrue(far.combat().forceMove(825), "825 BHABABYGRAB");
		GrabSync sync = new GrabSync(tr.ctl(), tr.hero());
		List<EnemyFighter> all = new ArrayList<>(List.of(near, far));
		sync.setPullers(() -> all);
		ScriptedInput in = new ScriptedInput(0, List.of());
		int pulls = 0;
		float err = Float.NaN;
		for (int i = 0; i < 20; i++) {
			in.advance(1 / 60f);
			tr.ctl().update(in, 0, 1 / 60f);
			near.update(1 / 60f);
			far.update(1 / 60f);
			sync.update(in, near, tr.enemyTweaks(), 1 / 60f); // 대상 = 가까운 적 (GameScene nearestEnemy)
			if (sync.pulling() == 1) {
				pulls++;
				Matrix4f t = SyncPull.target(far.pos, far.yaw, far.animator(), far.scale, tr.ctl().animator(), 1f);
				err = t.getTranslation(new Vector3f()).distance(tr.ctl().position);
			}
		}
		assertTrue(pulls > 5, "먼 아기 히드라 (공격 대상 = 영웅, 825 창) 가 끈다: " + pulls);
		assertTrue(tr.ctl().position.z > 20, "영웅이 먼 쪽으로 끌려감: " + tr.ctl().position + " 오차 " + err);
	}

	/**
	 * 끌리는 쪽은 끌리는 첫 프레임부터 루트 모션 없음 (원작 0x1EBF8C: 상대 이벤트 창을 같은 프레임에 0x206030 으로 갱신한 뒤 0x2441E0 → D = 단위).
	 * 엔진은 두 쪽 갱신 뒤에 끌기를 알게 되므로 그 틱의 루트 모션을 되돌린다 (BossSync·GrabSync): 되돌리면 이동·요가 갱신 전과 같다.
	 */
	@Test
	void undoRootMotionRestoresPose() throws Exception {
		GrabRelation.Trial t = trials("R_MIN.WAD").get();
		EnemyFighter en = t.enemy();
		en.pos.set(0, 0, -600);
		en.yaw = 0.4f;
		int move = -1;
		for (int id : new int[]{10, 11, 12, 13, 14, 15, 16}) {
			if (en.combat().forceMove(id)) {
				move = id;
				break;
			}
		}
		Assumptions.assumeTrue(move >= 0);
		float moved = 0;
		for (int i = 0; i < 120 && moved < 1; i++) {
			Vector3f p0 = new Vector3f(en.pos);
			float y0 = en.yaw;
			en.update(1 / 60f);
			moved = new Vector3f(en.pos).sub(p0).length();
			if (moved >= 1) {
				en.undoRootMotion();
				assertEquals(0, new Vector3f(en.pos).distance(p0), 1e-3f, "적 루트 모션 되돌림 (기술 " + move + ")");
				assertEquals(y0, en.yaw, 1e-5f);
			}
		}
		assertTrue(moved >= 1, "루트 모션이 있는 공격 기술");
		var ctl = t.ctl();
		t.hero().forceMove(GrabRelation.GRAB_MOVE); // GRAB: zero z 1.8 → −30.7 (앞으로)
		for (int i = 0; i < 10; i++) {
			ctl.updateCombat(t.hero(), new ScriptedInput(0, List.of()), 0, 1 / 60f, null);
		}
		Vector3f h0 = new Vector3f(ctl.position);
		ctl.updateCombat(t.hero(), new ScriptedInput(0, List.of()), 0, 1 / 60f, null);
		assertTrue(ctl.position.distance(h0) > 0.5f, "GRAB 루트 모션");
		ctl.undoRoot();
		assertEquals(0, ctl.position.distance(h0), 1e-3f, "영웅 루트 모션 되돌림");
	}

	/**
	 * 해골병 (ATHN01A 배 위 레기오네어, R_SKS) ○ 잡기 → □ (37 문서 §11.7): 영웅 220 MAISKB00 [0.4–1] □ → 221 MAISKB02, 영웅 기술 플래그 0x20 → 적도 221
	 * (SYNC 분기). 두 쪽 같은 애니 시간 (속도 1.0 — 영웅 클립 2.33 초, 적 2.0 초), 영웅 이벤트 22 [0–0.76] 이 적을 끌어 들어 올린다.
	 * 적 221 의 [0.40–1]! AUTO → 105 HITLAND 는 지상 전용 (분기 플래그 0x2) — 들린 적은 자유 이동 바닥 질의 (0x1EA200) 가 낙하 상태로 바꿔
	 * 착지할 때까지 막힌다. 예전엔 적이 늘 지상이라 적 진행률 0.4 (영웅 0.34) 에서 HITLAND 로 빠져 영웅만 허공을 꿰뚫었다.
	 */
	@Test
	void skeletonGrabSquareStaysPaired() throws Exception {
		GrabRelation.Trial t = trials("R_SKS.WAD").get();
		EnemyFighter en = t.enemy();
		en.setNavWorld(EnemyNav.flat(0));
		en.pos.set(0, 0, 0);
		en.yaw = 0;
		t.ctl().position.set(0, 0, -30);
		t.ctl().yaw = 0;
		final float dt = 1 / 60f;
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.05f, 0.05f, devple.gow.engine.input.PadButton.CIRCLE),
				new ScriptedInput.Step(0.6f, 0.05f, devple.gow.engine.input.PadButton.SQUARE)));
		GrabSync sync = new GrabSync(t.ctl(), t.hero());
		var anim = t.ctl().animator();
		boolean grabbed = false, air = false;
		int paired = 0;
		float heroAtEnd = -1;
		EnemyNav.Mode prevMode = null;
		for (int i = 0; i < 240 && heroAtEnd < 0; i++) {
			in.advance(dt);
			if (!sync.active()) {
				t.hero().update(in, dt);
				if (t.hero().active()) {
					t.ctl().updateCombat(t.hero(), in, 0, dt, en.pos);
				} else {
					t.ctl().update(in, 0, dt);
				}
			}
			en.update(dt);
			sync.update(in, en, t.enemyTweaks(), dt);
			Combatant h = t.hero(), e = en.combat();
			int hm = h.active() ? h.move().id() : -1, em = e.active() ? e.move().id() : -1;
			grabbed |= hm == 220 && em == 220;
			if (hm == 221 && em == 221) {
				paired++;
				assertEquals("maiskb02", h.clipName().toLowerCase());
				assertEquals("maiskb02", e.clipName().toLowerCase());
				assertEquals(anim.time(), en.animator().time(), 1e-4f, "같은 애니 시간 (틱 " + i + ")");
				if (h.progress() < 0.75f) {
					assertTrue(sync.active() && sync.pulling() == 2, "영웅 이벤트 22 [0–0.76] 가 적을 끈다 (틱 " + i + ")");
				}
				air |= en.nav().mode() == EnemyNav.Mode.AIR;
			} else if (hm == 221 && paired > 0) {
				assertEquals(105, em, "적 다음 기술 = HITLAND");
				assertEquals(EnemyNav.Mode.GROUND, prevMode, "HITLAND 는 착지 뒤 (지상 전용 분기)");
				heroAtEnd = h.progress();
			}
			prevMode = en.nav().mode();
		}
		assertTrue(grabbed, "○ → 220 MAISKB00 결합");
		assertTrue(paired > 60, "221 쌍 틱 수: " + paired);
		assertTrue(air, "들린 적은 낙하 상태 (0x1EA200 바닥 질의 실패 → +0x174 = 8)");
		assertTrue(heroAtEnd > 0.78f, "적은 영웅 221 의 후반까지 같은 애니 — 바닥 선분 끝 = 발 − 6.4 (0x1E3298) 라 날아가는 동안 지상이 아님 (예전 0.34 / 0.6): " + heroAtEnd);
		assertTrue(en.pos.z > 150, "던진 해골은 영웅 정면 (+z) 멀리 착지: z " + en.pos.z);
	}
}
