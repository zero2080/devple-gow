package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 원작 적 AI (17 문서) 헤드리스 검증: 결정 트리 디코딩, 접근·공격 주기, Block%, BlockMod, 공격 토큰 동시 공격 수, 전 적 스모크.
 */
class AiBrainTest {

	static final float DT = 1 / 60f;

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
		return rom;
	}

	private static WadFile permk;

	private static EnemyFactory.Kind kind(String wad) throws Exception {
		Path rom = rom();
		if (permk == null) {
			permk = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		}
		return EnemyFactory.load(Content.file(rom, wad), permk, -1);
	}

	/** 움직이지 않는 대상 (영웅 대역). 맞아도 무시. */
	private static Hittable dummy(Vector3f p) {
		return new Hittable() {
			public Vector3f position() {
				return p;
			}

			public float hp() {
				return 100;
			}

			public int classId() {
				return 0;
			}

			public boolean invulnerable() {
				return true;
			}

			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) {
				return Result.IGNORED;
			}
		};
	}

	@Test
	void decisionTreesDecode() throws Exception {
		EnemyFactory.Kind min = kind("R_MIN.WAD");
		AiData ai = AiData.load(min.assets().wad(), permk);
		DecisionTree t = ai.tree(50);
		assertNotNull(t);
		List<DecisionTree.Leaf> leaves = t.leaves();
		assertEquals(List.of("attack!", "taunt!"), leaves.stream().map(DecisionTree.Leaf::name).toList());
		DecisionTree.Attack axe = leaves.get(0).attacks().get(1);
		assertEquals(11, axe.move()); // AXECOMBO
		assertEquals(3, axe.costA());
		assertEquals(1000, axe.pauseMs());
		assertEquals(96f, axe.maxUnits(), 1e-3);
		assertEquals(17, leaves.get(0).blocks().getFirst().move()); // BLOCK
		assertEquals(new AiData.Soldier(4, 12, 10, 10), ai.soldier(0), "크라토스 Sold_000");
		assertEquals(2, ai.soldier(50).cost());
		AiTemplate tm = min.spawn().template();
		assertEquals(40f, tm.block);
		assertEquals(4, tm.tokenRadius);
		assertEquals(10, tm.waitRadius);
		assertTrue(tm.usesToken());
		assertEquals(0, tm.mode);
		// 해골 -C (021): 25 노드 (벽/로프 분기)
		EnemyFactory.Kind sks = kind("R_SKS.WAD");
		assertEquals(25, AiData.load(sks.assets().wad()).tree(21).size());
	}

	/** 한 마리 + 대상, 시간 진행. 공격 시작/끝 기록. */
	private record Run(EnemyFighter f, List<float[]> attacks) {
	}

	private static Run run(EnemyFighter f, Vector3f target, float seconds, int difficulty) {
		f.setDifficulty(difficulty);
		f.setFoe(dummy(target));
		List<float[]> at = new ArrayList<>(); // {시작, 끝, 기술, pause}
		DecisionTree.Attack cur = null;
		float[] rec = null;
		for (float t = 0; t < seconds; t += DT) {
			f.update(DT);
			DecisionTree.Attack a = f.brain().currentAttack();
			if (a != cur) {
				if (rec != null) {
					rec[1] = t;
				}
				rec = a != null ? new float[]{t, -1, a.move(), a.pauseMs()} : null;
				if (rec != null) {
					at.add(rec);
				}
				cur = a;
			}
		}
		return new Run(f, at);
	}

	/** 인지 시선 가림 (0x1D22B0 → sub_0011E3E8): 벽에 막히면 인지하지 않고, 트이면 전투로. */
	@Test
	void wallBlocksPerception() throws Exception {
		EnemyFactory.Kind k = kind("R_MIN.WAD");
		EnemyFighter f = k.spawn();
		f.pos.set(0, 0, -200);
		boolean[] wall = {true};
		f.brain().setSightBlocked((eye, to) -> wall[0]);
		f.setFoe(dummy(new Vector3f()));
		for (float t = 0; t < 3; t += DT) {
			f.update(DT);
		}
		assertTrue(f.brain().state() != AiBrain.State.COMBAT, "벽 너머: " + f.brain().state());
		wall[0] = false;
		for (float t = 0; t < 1; t += DT) {
			f.update(DT);
		}
		assertEquals(AiBrain.State.COMBAT, f.brain().state(), "시선이 트이면 인지");
	}

	/** 경보 (0x1D1AD8, 반경 0x48 × 16): 인지한 적이 반경 안의 동료를 전투로 끌어들인다. */
	@Test
	void perceptionAlertsNearbyAllies() throws Exception {
		AiTargets.clear();
		EnemyFactory.Kind k = kind("R_MIN.WAD");
		EnemyFighter seer = k.spawn(), near = k.spawn(), far = k.spawn();
		seer.pos.set(0, 0, -200);
		float r = new AiTemplate(k.tweaks(), seer.ai1()).alertRadius * 16f;
		near.pos.set(r * 0.5f, 0, -200);
		far.pos.set(r * 3f, 0, -200);
		near.brain().setSightBlocked((eye, to) -> true);
		far.brain().setSightBlocked((eye, to) -> true);
		Hittable tgt = dummy(new Vector3f());
		for (EnemyFighter f : List.of(seer, near, far)) {
			f.setFoe(tgt);
		}
		for (float t = 0; t < 0.5f; t += DT) {
			seer.update(DT);
			near.update(DT);
			far.update(DT);
		}
		assertEquals(AiBrain.State.COMBAT, seer.brain().state());
		assertEquals(AiBrain.State.COMBAT, near.brain().state(), "반경 " + r + " 안 → 경보");
		assertTrue(far.brain().state() != AiBrain.State.COMBAT, "반경 밖은 그대로");
	}

	/** 결정 트리 조건 5 (대상 상태): 영웅이 벽/로프/공중이면 1/3/2 (0x1A14D8). */
	@Test
	void targetLocomotionStateCondition() throws Exception {
		EnemyFighter f = kind("R_MIN.WAD").spawn();
		int[] st = {0};
		Hittable base = dummy(new Vector3f(0, 0, -50));
		f.setFoe(new Hittable() {
			public Vector3f position() { return base.position(); }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public boolean invulnerable() { return true; }
			public int locomotionState() { return st[0]; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
		});
		for (int s : new int[]{0, 1, 2, 3}) {
			st[0] = s;
			assertEquals(s, (int) f.brain().sense(DecisionTree.C_TARGET_STATE, 0));
		}
		assertEquals(0, (int) f.brain().sense(DecisionTree.C_SELF_STATE, 0), "적은 지상");
	}

	@Test
	void minotaurApproachesAndAttacksWithTemplateCadence() throws Exception {
		EnemyFactory.Kind k = kind("R_MIN.WAD");
		for (int diff : new int[]{0, 1, 3}) {
			EnemyFighter f = k.spawn();
			f.pos.set(0, 0, -400);
			Vector3f tp = new Vector3f();
			Run r = run(f, tp, 30f, diff);
			assertEquals(AiBrain.State.COMBAT, f.brain().state());
			assertTrue(f.pos.distance(tp) < 120, "접근: " + f.pos.distance(tp));
			assertTrue(f.brain().hasToken(), "혼자면 토큰 보유");
			List<float[]> axes = r.attacks().stream().filter(a -> a[2] == 10 || a[2] == 11).toList();
			assertTrue(axes.size() >= 3, "도끼 공격 " + axes.size() + " (난이도 " + diff + ")");
			float mult = new float[]{2f, 1f, 0.75f, 0.5f}[diff];
			for (int i = 0; i + 1 < r.attacks().size(); i++) {
				float[] a = r.attacks().get(i), b = r.attacks().get(i + 1);
				if (a[1] < 0) {
					continue;
				}
				// 공격 끝 → 다음 공격 시작 ≥ pause × "AI Recovery Time Mult"
				assertTrue(b[0] - a[1] >= a[3] / 1000f * mult - 2 * DT,
						String.format("간격 %.2f < %.2f (기술 %d)", b[0] - a[1], a[3] / 1000f * mult, (int) a[2]));
			}
		}
	}

	@Test
	void easyAttacksLessOftenThanImpossible() throws Exception {
		EnemyFactory.Kind k = kind("R_SKS.WAD");
		int[] n = new int[2];
		int i = 0;
		for (int diff : new int[]{0, 3}) {
			EnemyFighter f = k.spawn();
			f.pos.set(0, 0, -300);
			// 80 초: Sold 속도(128 유닛/s)로 칸 이동이 빨라져 Impossible 간격은 칸 대기(2 초)에 묶임 — 40 초면 칸 재선택 난수에 따라 같아질 수 있음
			Run r = run(f, new Vector3f(), 80f, diff);
			n[i++] = (int) r.attacks().stream().filter(a -> a[2] == 11 || a[2] == 12).count();
		}
		assertTrue(n[0] < n[1], "Easy " + n[0] + " < Impossible " + n[1]);
	}

	@Test
	void skeletonUsesTreeMoves() throws Exception {
		EnemyFighter f = kind("R_SKS.WAD").spawn();
		f.pos.set(200, 0, 0);
		Run r = run(f, new Vector3f(), 20f, 1);
		assertTrue(r.attacks().stream().anyMatch(a -> a[2] == 11), "SKU3HIT");
		for (float[] a : r.attacks()) {
			assertTrue(List.of(0, 1, 2, 11, 12).contains((int) a[2]), "결정 트리 밖 기술 " + a[2]);
		}
		assertEquals(0.87f, f.scale, 1e-3, "ScaleCenter 87");
	}

	private static int blockTrials(EnemyFighter f, int attackerMove, int trials) {
		if (f.brain().target() == null) {
			f.setFoe(dummy(new Vector3f(0, 0, 0)));
		}
		f.pos.set(0, 0, -60);
		int blocks = 0;
		for (int i = 0; i < trials; i++) {
			for (int s = 0; s < 600 && f.combat().active(); s++) {
				f.update(DT);
			}
			if (f.brain().tryBlock(attackerMove, true)) {
				blocks++;
				assertTrue(f.combat().guarding(), "방어 자세");
			}
		}
		return blocks;
	}

	@Test
	void blocksAtTemplateRate() throws Exception {
		int n = 400;
		int min = blockTrials(kind("R_MIN.WAD").spawn(), 999, n);
		assertEquals(0.40, min / (double) n, 0.07, "미노타우르스 Block 40%: " + min);
		int sks = blockTrials(kind("R_SKS.WAD").spawn(), 999, n);
		assertEquals(0, sks, "해골 Lv1 Block 0%");
	}

	@Test
	void blockModLearnsRepeatedMoves() throws Exception {
		EnemyFactory.Kind k = kind("R_MIN.WAD");
		EnemyFighter f = k.spawn();
		f.setFoe(dummy(new Vector3f()));
		AiTargets.Slot slot = f.brain().slot();
		// BlockMod 050 "Move 1" = 영웅 기술 7, Min 3, Min Block 50, Limit 9
		assertEquals(new AiData.BlockEntry(7, 3, 50f, 9), AiData.blockMod(k.tweaks(), 50).get(1));
		for (int i = 0; i < 9; i++) {
			slot.history.onMoveStart(7);
		}
		assertEquals(9, slot.history.count(7));
		assertEquals(10, blockTrials(f, 7, 10), "한계 횟수 이상이면 항상 방어");
		assertTrue(slot.history.count(7) == 9, "기록 유지 (40초 창 안)");
		slot.history.tick(40001);
		assertEquals(0, slot.history.count(7), "40초 지나면 기록 소멸");
	}

	@Test
	void attackTokensLimitSimultaneousAttackers() throws Exception {
		for (String wad : new String[]{"R_SKS.WAD", "R_MIN.WAD"}) {
			EnemyFactory.Kind k = kind(wad);
			Vector3f tp = new Vector3f();
			Hittable target = dummy(tp);
			List<EnemyFighter> group = new ArrayList<>();
			for (int i = 0; i < 7; i++) {
				EnemyFighter f = k.spawn();
				double a = i * Math.PI * 2 / 7;
				f.pos.set((float) Math.sin(a) * 260, 0, (float) Math.cos(a) * 260);
				f.setFoe(target);
				group.add(f);
			}
			AiTargets.Slot slot = AiTargets.slot(target);
			assertSame(slot, group.getFirst().brain().slot(), "대상 슬롯 공유");
			int cost = soldierCost(wad);
			int maxHolders = slot.sold.capacity() / cost;
			int seenHolders = 0, seenAttackers = 0, attacks = 0;
			for (float t = 0; t < 25f; t += DT) {
				int holders = 0, attackers = 0, usedA = 0;
				for (EnemyFighter f : group) {
					f.update(DT);
				}
				for (EnemyFighter f : group) {
					holders += f.brain().hasToken() ? 1 : 0;
					DecisionTree.Attack a = f.brain().currentAttack();
					if (a != null && a.costA() > 0) {
						attackers++;
						usedA += a.costA();
					}
				}
				assertTrue(holders <= maxHolders, wad + " 토큰 보유 " + holders + " > " + maxHolders);
				assertTrue(usedA <= slot.sold.capA(), wad + " 공격 비용 합 " + usedA);
				assertEquals(holders * cost, slot.tokenBudget(), "예산 = 보유자 × 비용");
				seenHolders = Math.max(seenHolders, holders);
				seenAttackers = Math.max(seenAttackers, attackers);
			}
			for (EnemyFighter f : group) {
				attacks += f.brain().attacksStarted;
			}
			assertEquals(maxHolders, seenHolders, wad + " 토큰 최대치까지 사용");
			assertTrue(seenAttackers >= 1 && attacks > 3, wad + " 공격 발생 " + attacks);
			// 해골: 용량 10 / 비용 3 → 3 / 미노: 비용 3·5 → 최대 3 (3+3+3)
			assertTrue(seenAttackers <= 3, wad + " 동시 공격 " + seenAttackers);
		}
	}

	private static int soldierCost(String wad) {
		return wad.equals("R_SKS.WAD") ? 4 : 2;
	}

	@Test
	void everyEnemyKindRunsTenSeconds() throws Exception {
		Path rom = rom();
		if (permk == null) {
			permk = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		}
		Map<String, List<Integer>> wads = new TreeMap<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(rom, "R_*.WAD")) {
			for (Path p : ds) {
				WadFile w = WadFile.load(p);
				List<Integer> ids = new ArrayList<>();
				for (WadTag t : w.tags()) {
					if (t.name() != null && t.name().startsWith("TWK_AI1_")) {
						ids.add(Integer.parseInt(t.name().substring(8)));
					}
				}
				if (!ids.isEmpty()) {
					wads.put(p.getFileName().toString(), ids);
				}
			}
		}
		int kinds = 0, spawned = 0;
		for (Map.Entry<String, List<Integer>> e : wads.entrySet()) {
			EnemyFactory.Kind k;
			try {
				k = EnemyFactory.load(Content.file(rom, e.getKey()), permk, -1);
			} catch (IllegalArgumentException ex) {
				continue; // 스켈레톤 없는 사운드 전용 WAD (R_A1A … "FkSnd")
			}
			kinds++;
			Vector3f tp = new Vector3f();
			Hittable target = dummy(tp);
			for (int id : e.getValue()) {
				EnemyFighter f = k.spawn(id);
				f.pos.set(0, 0, -200);
				f.setFoe(target);
				for (float t = 0; t < 10f; t += DT) {
					f.update(DT);
				}
				assertTrue(Float.isFinite(f.pos.x) && Float.isFinite(f.pos.z), e.getKey() + " " + id);
				spawned++;
			}
		}
		assertTrue(kinds >= 25, "적 종류 " + kinds);
		System.out.println("AI 스모크: 종류 " + kinds + ", 템플릿 " + spawned);
	}
}
