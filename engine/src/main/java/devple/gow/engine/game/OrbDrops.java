package devple.gow.engine.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 오브 드롭 (10 문서 §11.1). OrbEmitter 템플릿(예 "MIN Death")의 슬롯 3개(Health/Magic/Weapon) 각각:
 * {@code <Slot> Template} = Orb 템플릿 번호, {@code Normal <Slot> Prob/Min/Max} = 확률·개수.
 * 실제 오브 종류/포인트는 Orb 템플릿({@code Type} 0 체력/1 마법/2 경험치, {@code Points})이 정한다.
 * 플레이어 HP/MP 가 임계 미만이면 Normal 대신 {@code Low Health/Magic Prob/Min/Max} 세트 (0x225AB0).
 */
public final class OrbDrops {

	/** 오브 하나: 종류 (0 체력 / 1 마법 / 2 붉은), 포인트, Orb 템플릿 번호 ({@link OrbPickups} 가 GO·흡수 값을 읽음). */
	public record Orb(int type, float points, int template) {
	}

	private static final String[] SLOTS = {"Health", "Magic", "Weapon"};

	private OrbDrops() {
	}

	public static List<Orb> roll(Tweaks tw, String emitterName, Random rnd, float playerHp, float playerMp) {
		return rollNode(tw, tw.findTemplate("/TweakTemplates/OrbEmitter/", emitterName), rnd, playerHp, playerMp);
	}

	/** 템플릿 노드로 직접 (부서지는 물체 Brkb +0x28 = OrbEmitter 번호, 41 문서). node 가 null 이면 빈 목록. */
	public static List<Orb> rollNode(Tweaks tw, String node, Random rnd, float playerHp, float playerMp) {
		return rollNode(tw, node, rnd, playerHp, playerMp, true);
	}

	/**
	 * OrbEmitter_Emit (0x2258D8, 0x225AB0): 개수 세트는 셋 중 하나 — HP &lt; Health Threshold 면 Low Health (체력 슬롯만),
	 * 아니고 MP &lt; Magic Threshold 면 Low Magic (마법 슬롯만), 아니면 Normal (세 슬롯). 마법 오브 (종류 1) 는 마법을 가졌을 때만 (0x3044B7).
	 */
	public static List<Orb> rollNode(Tweaks tw, String node, Random rnd, float playerHp, float playerMp, boolean hasMagic) {
		List<Orb> out = new ArrayList<>();
		if (node == null) {
			return out;
		}
		String set;
		String[] slots;
		if (playerHp < tw.i16(node, "Health Threshold", 0)) {
			set = "Low ";
			slots = new String[]{"Health"};
		} else if (playerMp < tw.i16(node, "Magic Threshold", 0)) {
			set = "Low ";
			slots = new String[]{"Magic"};
		} else {
			set = "Normal ";
			slots = SLOTS;
		}
		for (String slot : slots) {
			Orb orb = orb(tw, tw.i16(node, slot + " Template", -1));
			if (orb == null || orb.type() == 1 && !hasMagic) {
				continue;
			}
			addSlot(out, orb, rnd, tw.f(node, set + slot + " Prob", 0), tw.i16(node, set + slot + " Min", 0),
					tw.i16(node, set + slot + " Max", 0));
		}
		return out;
	}

	private static void addSlot(List<Orb> out, Orb orb, Random rnd, float prob, int min, int max) {
		if (prob <= 0 || rnd.nextFloat() >= prob) {
			return;
		}
		int n = min + (max > min ? rnd.nextInt(max - min + 1) : 0);
		for (int i = 0; i < n; i++) {
			out.add(orb);
		}
	}

	/** Orb 템플릿 → (종류, 포인트). 없으면 null (99 등 = 슬롯 비어 있음). */
	public static Orb orb(Tweaks tw, int template) {
		if (template < 0) {
			return null;
		}
		String node = String.format("/TweakTemplates/Orb/%03d/", template);
		float pts = tw.f(node, "Points", -1);
		if (pts < 0) {
			return null;
		}
		return new Orb(tw.i16(node, "Type", 2), pts, template);
	}
}
