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
		for (AnimationSet.Group g : animation.groups) {
			for (Animation a : g.acts) {
				animByHash.putIfAbsent(TweakFile.hashUpper(a.name), a);
			}
		}
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

	public int moveCount() {
		return moves.size();
	}
}
