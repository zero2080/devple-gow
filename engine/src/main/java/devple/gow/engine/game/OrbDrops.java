package devple.gow.engine.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 오브 드롭 (10 문서 §11.1). OrbEmitter 템플릿(예 "MIN Death")의 슬롯 3개(Health/Magic/Weapon) 각각:
 * {@code <Slot> Template} = Orb 템플릿 번호, {@code Normal <Slot> Prob/Min/Max} = 확률·개수.
 * 실제 오브 종류/포인트는 Orb 템플릿({@code Type} 0 체력/1 마법/2 경험치, {@code Points})이 정한다.
 * 플레이어 HP/MP 가 임계 미만이면 {@code Low Health/Magic Prob/Min/Max} 추가 드롭.
 */
public final class OrbDrops {

	public record Orb(int type, float points) {
	}

	private static final String[] SLOTS = {"Health", "Magic", "Weapon"};

	private OrbDrops() {
	}

	public static List<Orb> roll(Tweaks tw, String emitterName, Random rnd, float playerHp, float playerMp) {
		List<Orb> out = new ArrayList<>();
		String node = tw.findTemplate("/TweakTemplates/OrbEmitter/", emitterName);
		if (node == null) {
			return out;
		}
		for (String slot : SLOTS) {
			int template = tw.i16(node, slot + " Template", -1);
			Orb orb = orb(tw, template);
			if (orb == null) {
				continue;
			}
			addSlot(out, orb, rnd, tw.f(node, "Normal " + slot + " Prob", 0),
					tw.i16(node, "Normal " + slot + " Min", 0), tw.i16(node, "Normal " + slot + " Max", 0));
		}
		float hpTh = tw.i16(node, "Health Threshold", 0);
		if (playerHp < hpTh) {
			Orb hpOrb = orb(tw, tw.i16(node, "Health Template", -1));
			if (hpOrb != null) {
				addSlot(out, hpOrb, rnd, tw.f(node, "Low Health Prob", 0), tw.i16(node, "Low Health Min", 0),
						tw.i16(node, "Low Health Max", 0));
			}
		}
		float mpTh = tw.i16(node, "Magic Threshold", 0);
		if (playerMp < mpTh) {
			Orb mpOrb = orb(tw, tw.i16(node, "Magic Template", -1));
			if (mpOrb != null) {
				addSlot(out, mpOrb, rnd, tw.f(node, "Low Magic Prob", 0), tw.i16(node, "Low Magic Min", 0),
						tw.i16(node, "Low Magic Max", 0));
			}
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
		return new Orb(tw.i16(node, "Type", 2), pts);
	}
}
