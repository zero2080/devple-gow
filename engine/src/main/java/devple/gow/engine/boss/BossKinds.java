package devple.gow.engine.boss;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.combat.AiData;
import devple.gow.engine.combat.AiTargets;
import devple.gow.engine.combat.EnemyFactory;
import devple.gow.engine.game.Tweaks;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.wad.WadFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 레벨 WAD 안에 들어 있는 보스 (적 리소스 R_* 가 아님) 를 {@link EnemyFactory.Kind} 로 구성한다.
 * <ul>
 *   <li>히드라 ATHN01E: 스켈레톤 bha (MDL_BHA/ANM_bha), AI1 250, CombatFileSet 250 = 250/251/252/256 — 튜닝 일부는 R_AHA</li>
 *   <li>메두사 여왕 ATHN02D: me3, AI1 062, 세트 062 = 066/067/068</li>
 *   <li>아레스 ATHN08A: bar, AI1 600, 세트 600 = 600/602/604 / ATHN08C: ba2, AI1 601, 세트 601 = 601/603/605</li>
 * </ul>
 * EnemyFactory 는 "WAD 의 첫 CombatFileSet" 을 쓰지만 레벨 WAD 에는 세트가 여럿이라(ATHN01E: 250, 253) 번호를 지정한다.
 */
public final class BossKinds {

	private BossKinds() {
	}

	/**
	 * @param level    레벨 WAD (스켈레톤·모델·애니·CombatFile·AI1·DTre)
	 * @param extras   추가 튜닝 WAD (예 R_AHA: PlayFX/RegenBar 등). 없으면 비움
	 * @param skeleton 스켈레톤(=CXT) 이름
	 * @param ai1      AI1 템플릿
	 * @param set      CombatFileSet 번호
	 */
	public static EnemyFactory.Kind load(Path level, WadFile permk, List<Path> extras, String skeleton, int ai1, int set)
			throws IOException {
		WadAssets assets = new WadAssets(level);
		return load(assets, permk, extras, skeleton, ai1, set);
	}

	public static EnemyFactory.Kind load(WadAssets assets, WadFile permk, List<Path> extras, String skeleton, int ai1, int set)
			throws IOException {
		WadFile wad = assets.wad();
		Tweaks tw = new Tweaks();
		tw.load(permk);
		List<WadFile> extraWads = new ArrayList<>();
		for (Path p : extras) {
			WadFile w = WadFile.load(p);
			extraWads.add(w);
			tw.load(w);
		}
		tw.load(wad);
		Skeleton sk = Skeleton.loadAll(wad).stream().filter(s -> s.name.equalsIgnoreCase(skeleton)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("보스 스켈레톤 없음: " + skeleton + " in " + wad.name()));
		List<String> refs = Skeleton.groupRefs(wad, sk.name);
		String mdl = refs.stream().filter(r -> r.startsWith("MDL_")).findFirst().orElseThrow();
		String anm = refs.stream().filter(r -> r.startsWith("ANM_")).findFirst().orElseThrow();
		AnimationSet anims = AnimationSet.load(wad, anm);
		String setNode = String.format("/TweakTemplates/CombatFileSet/%03d/", set);
		List<String> files = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			int f = tw.i16(setNode, "File " + i, -1);
			if (f >= 0) {
				files.add(String.format("TWK_CombatFile_%03d", f));
			}
		}
		if (files.isEmpty()) {
			throw new IllegalArgumentException("CombatFileSet 없음: " + setNode);
		}
		List<WadFile> aiWads = new ArrayList<>();
		aiWads.add(wad);
		aiWads.addAll(extraWads);
		aiWads.add(permk);
		AiData ai = AiData.load(aiWads.toArray(WadFile[]::new));
		AiTargets.setHeroSoldier(ai.soldier(0));
		String idle = clip(anims, "standidle", "idle");
		String walk = clip(anims, "walkforward", "walkfront", "runforward", "walk", "idle");
		String animNode = tw.findNode("/Animation/go" + sk.name + "/");
		return new EnemyFactory.Kind(assets, tw, sk, anims, mdl, ai1, files, new int[0], -1, idle, walk,
				animNode != null ? animNode : "/Animation/go" + sk.name + "/", ai);
	}

	/** 이름에 접두/포함어가 있는 첫 골격 애니 (보스 클립은 "BHAbossIdle", "BARS3StandIdle" 처럼 접두사가 붙음). */
	static String clip(AnimationSet set, String... keys) {
		for (String k : keys) {
			for (AnimationSet.Group g : set.groups) {
				for (Animation a : g.acts) {
					if (a.hasSkeletalData() && a.name.toLowerCase(Locale.ROOT).contains(k)) {
						return a.name;
					}
				}
			}
		}
		return set.groups.getFirst().acts.getLast().name;
	}
}
