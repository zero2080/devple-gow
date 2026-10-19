package devple.gow.engine.combat;

import devple.gow.engine.asset.AnimatedModel;
import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.render.Shader;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.combat.CombatFile.Branch;
import devple.gow.tools.combat.CombatFile.Move;
import devple.gow.tools.wad.WadFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 적 리소스 WAD(R_MIN, R_SKS, R_CYW …) 하나에서 적을 자동 구성한다.
 * <ul>
 *   <li>스켈레톤: MDL_ 와 ANM_ 를 참조하는 것 중 WAD 이름과 같은 것(R_HAR → har), 없으면 관절 수 최대
 *       (석화 파편 "harstonedbrk" 처럼 관절이 더 많은 부속 스켈레톤이 있음)</li>
 *   <li>AI1 템플릿: WAD 의 첫 {@code /TweakTemplates/AI1/NNN/} (또는 지정 번호)</li>
 *   <li>CombatFileSet: 같은 WAD 의 첫 세트, {@code File 0..7} (번호가 AI1 과 다른 경우가 많아 "같은 WAD" 규칙 — 10 문서 §16)</li>
 *   <li>공격 목록: REQUEST0 루트 액션 중 공격 판정이 있고 방어 자세가 아닌 기술 / 방어: guard == 1 인 기술
 *       (결정 트리 없는 적의 대체 AI 용)</li>
 *   <li>AI: TWK_DTre 결정 트리 / TWK_Sold 병사 구조체 ({@link AiData})</li>
 * </ul>
 */
public final class EnemyFactory {

	/** 렌더까지 포함한 적 한 종류 (같은 종류 여러 마리가 공유). */
	public record Kind(WadAssets assets, Tweaks tweaks, Skeleton skeleton, AnimationSet anims, String model, int ai1,
					   List<String> combatFiles, int[] attacks, int block, String idle, String walk, String animNode,
					   AiData ai) {

		public EnemyFighter spawn() {
			return spawn(ai1);
		}

		/** 같은 WAD 의 다른 AI1 템플릿(예 R_SKA: 031 궁수 / 032 궁수 -P / 039 dumbArcher)으로 생성. */
		public EnemyFighter spawn(int ai1Template) {
			return new EnemyFighter(assets.wad(), tweaks, skeleton, anims, ai1Template, combatFiles, attacks, block, idle,
					walk, 30f, animNode, ai);
		}

		/** 이 적 WAD 에 해당 AI1 템플릿이 있는지. */
		public boolean hasTemplate(int ai1Template) {
			return tweaks.findNode(String.format("/TweakTemplates/AI1/%03d/", ai1Template)) != null;
		}

		public AnimatedModel buildModel(Shader skinned) {
			return new AnimatedModel(assets, assets.requireModel(model), skeleton, skinned);
		}
	}

	private EnemyFactory() {
	}

	public static Kind load(Path wadPath, WadFile permk, int ai1OrMinus1) throws IOException {
		WadAssets assets = new WadAssets(wadPath);
		WadFile wad = assets.wad();
		Tweaks tw = new Tweaks();
		tw.load(permk);
		tw.load(wad);
		Tweaks own = new Tweaks();
		own.load(wad);
		String base = wadPath.getFileName().toString().replaceFirst("(?i)\\.WAD$", "").replaceFirst("^R_", "").toLowerCase(Locale.ROOT);
		Skeleton best = null;
		String mdl = null, anm = null;
		boolean bestNamed = false;
		for (Skeleton s : Skeleton.loadAll(wad)) {
			List<String> refs = Skeleton.groupRefs(wad, s.name);
			String m = refs.stream().filter(r -> r.startsWith("MDL_")).findFirst().orElse(null);
			String a = refs.stream().filter(r -> r.startsWith("ANM_")).findFirst().orElse(null);
			boolean named = s.name.equalsIgnoreCase(base);
			if (m != null && a != null && assets.gowModel(m) != null
					&& (best == null || (named && !bestNamed) || (named == bestNamed && s.size() > best.size()))) {
				best = s;
				bestNamed = named;
				mdl = m;
				anm = a;
			}
		}
		if (best == null) {
			throw new IllegalArgumentException("적 스켈레톤 없음: " + wadPath);
		}
		AnimationSet set = AnimationSet.load(wad, anm);
		String ai1Node = ai1OrMinus1 >= 0 ? String.format("/TweakTemplates/AI1/%03d/", ai1OrMinus1)
				: own.firstNode("/TweakTemplates/AI1/");
		int ai1 = Integer.parseInt(ai1Node.split("/")[3]);
		String setNode = own.firstNode("/TweakTemplates/CombatFileSet/");
		List<String> files = new ArrayList<>();
		for (int i = 0; i < 8 && setNode != null; i++) {
			int f = tw.i16(setNode, "File " + i, -1);
			if (f >= 0) {
				files.add(String.format("TWK_CombatFile_%03d", f));
			}
		}
		List<Integer> attacks = new ArrayList<>();
		int block = -1;
		List<CombatFile> cfs = new ArrayList<>(CombatFile.fromWad(wad));
		cfs.addAll(CombatFile.fromWad(permk));
		for (CombatFile cf : cfs) {
			if (!files.contains(cf.name())) {
				continue;
			}
			for (Branch b : cf.actions()) {
				Move m = cf.moves().stream().filter(x -> x.id() == b.target()).findFirst().orElse(null);
				if (b.input() != 0 || m == null) {
					continue;
				}
				if (m.guard() == 1) {
					block = block < 0 ? m.id() : block;
				} else if (!m.attacks().isEmpty() && m.attacks().stream().anyMatch(a -> a.damage() > 0)) {
					attacks.add(m.id());
				}
			}
		}
		String idle = clip(set, "standidle", "idle");
		String walk = clip(set, "walkfront", "walkforward", "runfront", "walk");
		String animNode = tw.findNode("/Animation/go" + best.name + "/");
		// 결정 트리·병사 구조체 (17 문서). 영웅 Sold(000)는 공격 토큰 용량 (R_PERMK)
		AiData ai = AiData.load(wad, permk);
		AiTargets.setHeroSoldier(ai.soldier(0));
		return new Kind(assets, tw, best, set, mdl, ai1, files, attacks.stream().mapToInt(Integer::intValue).toArray(), block,
				idle, walk, animNode != null ? animNode : "/Animation/go" + best.name + "/", ai);
	}

	private static String clip(AnimationSet set, String... prefs) {
		for (String p : prefs) {
			for (AnimationSet.Group g : set.groups) {
				for (Animation a : g.acts) {
					if (a.hasSkeletalData() && a.name.toLowerCase(Locale.ROOT).startsWith(p)) {
						return a.name;
					}
				}
			}
		}
		return set.groups.getFirst().acts.getFirst().name;
	}
}
