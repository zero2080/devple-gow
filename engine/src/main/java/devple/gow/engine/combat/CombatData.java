package devple.gow.engine.combat;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.combat.CombatFile.Branch;
import devple.gow.tools.combat.CombatFile.Move;
import devple.gow.tools.tweak.TweakFile;
import devple.gow.tools.wad.WadFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전투 그래프 데이터 (10 문서 §2). 기술 ID 는 파일 간 공통 번호 공간이므로 전역 맵으로 보관.
 * 기술 → 애니메이션은 이름의 대문자 해시({@link TweakFile#hashUpper})로 연결.
 */
public final class CombatData {

	private final Map<Integer, Move> moves = new HashMap<>();
	private final List<Branch> rootActions = new ArrayList<>();
	private final Map<Integer, Animation> animByHash = new HashMap<>();

	/**
	 * @param files     사용할 CombatFile 이름 (영웅 = TWK_CombatFile_000..006, CombatFileSet 000 "Hero")
	 * @param animation 애니메이션 세트 (ANM_hero)
	 */
	public CombatData(WadFile wad, List<String> files, AnimationSet animation) {
		for (CombatFile f : CombatFile.fromWad(wad)) {
			if (!files.contains(f.name())) {
				continue;
			}
			for (Move m : f.moves()) {
				moves.put(m.id(), m);
			}
			rootActions.addAll(f.actions());
		}
		this.animation = animation;
		reindexAnimations();
	}

	private final AnimationSet animation;

	/** ANM 그룹 플래그 0x20 = 블렌드 그룹 (궁수 "shoot" 0x80020, 아기 히드라 "BHAbabyBite" 0x40020, BMI 주먹 …). */
	static final int GROUP_BLEND = 0x20;

	/**
	 * 외부 애니 그룹(잡기 MAI* 등, 적 WAD 의 0x10003)을 붙인 뒤 다시 색인.
	 * 블렌드 그룹 (플래그 0x20) 은 그룹 이름으로도 찾는다 — 기술 애니가 그룹을 가리키는 것 (궁수 SHOOT = 그룹 "shoot" 의 Ashoot00/01/02,
	 * 아기 히드라 물기·불기, BMI 주먹) 이 있다. 원작 0x1F6FC8 → 0x1006F8 → 0x107200 은 그룹 플래그 0x20 이면 그 그룹 act 를 모두 모아
	 * (+0xCC 목록, +0xDC 개수) 겨눔 값으로 섞는다 [확실: 모으기 / 근사: 엔진은 가운데 act (블렌드 번호 (n−1)/2 — 3 개면 Center) 하나].
	 * 전에는 찾지 못해 그 기술이 시작 프레임에 끝나 이벤트 (SCR_ArcherShoot · 소리) 가 하나도 나지 않았다 (43 §17).
	 */
	public void reindexAnimations() {
		for (AnimationSet.Group g : animation.groups) {
			for (Animation a : g.acts) {
				animByHash.putIfAbsent(TweakFile.hashUpper(a.name), a);
			}
		}
		for (AnimationSet.Group g : animation.groups) {
			Animation c = blendCentre(g);
			if (c != null && g.name != null && !g.name.isEmpty()) {
				animByHash.putIfAbsent(TweakFile.hashUpper(g.name), c);
			}
		}
	}

	/** 블렌드 그룹의 가운데 act (블렌드 번호 (최대)/2, 없으면 첫 act). 블렌드 그룹이 아니면 null. */
	static Animation blendCentre(AnimationSet.Group g) {
		if ((g.flags & GROUP_BLEND) == 0 || g.acts.isEmpty()) {
			return null;
		}
		int max = g.acts.stream().mapToInt(a -> a.blendIndex).max().orElse(0);
		int want = Math.max(0, max) / 2;
		return g.acts.stream().filter(a -> a.blendIndex == want).findFirst().orElse(g.acts.getFirst());
	}

	public static List<String> heroFiles() {
		List<String> l = new ArrayList<>();
		for (int i = 0; i <= 6; i++) {
			l.add(String.format("TWK_CombatFile_%03d", i));
		}
		return l;
	}

	public Move move(int id) {
		return moves.get(id);
	}

	public List<Branch> rootActions() {
		return rootActions;
	}

	public Animation animation(Move m) {
		return animByHash.get(m.animHash());
	}

	/** 애니 이름 해시(Hash127Upper)로 조회. */
	public Animation animation(int animHash) {
		return animByHash.get(animHash);
	}

	public int moveCount() {
		return moves.size();
	}
}
