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
			EnemyFighter f = new EnemyFighter(assets.wad(), tweaks, skeleton, anims, ai1Template, combatFiles, attacks, block, idle,
					walk, 30f, animNode, ai);
			// 원작 CDV 히트 볼륨 (34 문서) — 종류마다 같은 CDV, 인스턴스마다 포즈 상태가 따로
			f.combat().setHitVolumes(HitVolumes.forObject(assets.wad(), skeleton));
			return f;
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

	/**
	 * 레벨 WAD 자체에 정의된 AI1 템플릿(예 ATHN01D 301 "AOXF1")의 오브젝트 이름: 템플릿 "Instance Name" 의 소문자
	 * = 같은 레벨 WAD 의 스켈레톤 오브젝트 (aoxf1: MDL_aoXF1 / ANM_aoxf1 / CDV_aoXF1, 36 문서 §4). 템플릿이 없으면 null.
	 */
	public static String levelTemplateObject(Tweaks levelTweaks, int ai1) {
		String node = levelTweaks.findNode(String.format("/TweakTemplates/AI1/%03d/", ai1));
		String n = node == null ? null : levelTweaks.s(node, "Instance Name", null);
		return n == null || n.isEmpty() ? null : n.toLowerCase(Locale.ROOT);
	}

	/** 레벨이 쓰는 적 리소스 WAD 이름: RSRCS 태그(0x1F4)의 24바이트 이름들 → "R_&lt;이름&gt;" (대문자, 11 문서 §6.3 Slots 힙). */
	public static List<String> resourceWads(WadFile level) {
		List<String> out = new ArrayList<>();
		for (devple.gow.tools.wad.WadTag t : level.tags()) {
			if (t.tag() != devple.gow.tools.wad.WadTagType.RESOURCES || !t.hasData()) {
				continue;
			}
			var d = t.data();
			for (int off = 0; off + 24 <= d.limit(); off += 24) {
				int n = 0;
				while (n < 24 && d.get(off + n) != 0) {
					n++;
				}
				if (n == 0) {
					continue;
				}
				byte[] b = new byte[n];
				d.get(off, b);
				String name = "R_" + new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
				if (!out.contains(name)) {
					out.add(name);
				}
			}
		}
		return out;
	}

	public static Kind load(Path wadPath, WadFile permk, int ai1OrMinus1) throws IOException {
		return load(wadPath, permk, ai1OrMinus1, null);
	}

	/** skeleton: 고를 스켈레톤 이름 (null 이면 WAD 이름 → 관절 수 최대). 레벨 자체 템플릿은 레벨 WAD + {@link #levelTemplateObject}. */
	public static Kind load(Path wadPath, WadFile permk, int ai1OrMinus1, String skeleton) throws IOException {
		WadAssets assets = new WadAssets(wadPath);
		WadFile wad = assets.wad();
		Tweaks tw = new Tweaks();
		tw.load(permk);
		tw.load(wad);
		Tweaks own = new Tweaks();
		own.load(wad);
		String base = skeleton != null ? skeleton
				: wadPath.getFileName().toString().replaceFirst("(?i)\\.WAD$", "").replaceFirst("^R_", "").toLowerCase(Locale.ROOT);
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
		if (best != null && skeleton != null && !bestNamed) {
			// 템플릿 Instance Name 과 같은 오브젝트가 없음 (ATHN01C AI1 252 "hydra enc2"): CombatFileSet 기술 애니를 가장 많이 가진 스켈레톤 [추정]
			Skeleton byClips = byCombatClips(wad, permk, own, assets);
			if (byClips != null) {
				best = byClips;
				bestNamed = true;
				List<String> refs = Skeleton.groupRefs(wad, best.name);
				mdl = refs.stream().filter(r -> r.startsWith("MDL_")).findFirst().orElse(null);
				anm = refs.stream().filter(r -> r.startsWith("ANM_")).findFirst().orElse(null);
			}
		}
		if (best == null || skeleton != null && !bestNamed) {
			throw new IllegalArgumentException("적 스켈레톤 없음: " + wadPath + (skeleton != null ? " " + skeleton : ""));
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

	/** 레벨 WAD 의 첫 CombatFileSet 기술 애니 해시를 가장 많이 가진 (모델 있는) 스켈레톤 — 없으면 null. */
	private static Skeleton byCombatClips(WadFile wad, WadFile permk, Tweaks own, WadAssets assets) {
		String setNode = own.firstNode("/TweakTemplates/CombatFileSet/");
		if (setNode == null) {
			return null;
		}
		Tweaks tw = new Tweaks();
		tw.load(permk);
		tw.load(wad);
		java.util.Set<String> files = new java.util.HashSet<>();
		for (int i = 0; i < 8; i++) {
			int f = tw.i16(setNode, "File " + i, -1);
			if (f >= 0) {
				files.add(String.format("TWK_CombatFile_%03d", f));
			}
		}
		java.util.Set<Integer> hashes = new java.util.HashSet<>();
		for (CombatFile cf : CombatFile.fromWad(wad)) {
			if (files.contains(cf.name())) {
				cf.moves().forEach(m -> hashes.add(m.animHash()));
			}
		}
		Skeleton best = null;
		int bestHits = 0;
		for (Skeleton s : Skeleton.loadAll(wad)) {
			List<String> refs = Skeleton.groupRefs(wad, s.name);
			String m = refs.stream().filter(r -> r.startsWith("MDL_")).findFirst().orElse(null);
			String a = refs.stream().filter(r -> r.startsWith("ANM_")).findFirst().orElse(null);
			if (m == null || a == null || assets.gowModel(m) == null) {
				continue;
			}
			int hits = 0;
			for (AnimationSet.Group g : AnimationSet.load(wad, a).groups) {
				for (Animation an : g.acts) {
					if (hashes.contains(devple.gow.tools.tweak.TweakFile.hashUpper(an.name))) {
						hits++;
					}
				}
			}
			if (hits > bestHits) {
				best = s;
				bestHits = hits;
			}
		}
		return best;
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
