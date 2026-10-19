package devple.gow.engine.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 히트 카운터와 마일스톤 보너스 (10 문서 §11.2).
 * {@code /Player/Hit Counter Reset Time}(1초) 동안 적중이 없으면 0. {@code /Player/Bonus/Milestone n Hits}(short) 도달 시
 * {@code Template n} → {@code /TweakTemplates/Bonus/NNN/} (해시 #eed31206 = 붉은 오브 수, #2af48b79 = 메시지 ID).
 */
public final class HitCounter {

	public record Reward(int hits, int redOrbs, int messageId) {
	}

	private static final int BONUS_ORBS = 0xeed31206, BONUS_MSG = 0x2af48b79;

	private final List<int[]> milestones = new ArrayList<>();
	private final float resetTime;
	private int hits;
	private float sinceHit;
	private int nextMilestone;

	public HitCounter(Tweaks tw) {
		resetTime = tw.f("/Player/", "Hit Counter Reset Time", 1f);
		for (int i = 0; i < 32; i++) {
			int h = tw.i16("/Player/Bonus/", "Milestone " + i + " Hits", -1);
			int t = tw.i16("/Player/Bonus/", "Template " + i, -1);
			if (h <= 0 || t < 0) {
				break;
			}
			String node = String.format("/TweakTemplates/Bonus/%03d/", t);
			int orbs = (int) Float.intBitsToFloat(tw.rawHash(node, BONUS_ORBS).orElse(0));
			int msg = tw.rawHash(node, BONUS_MSG).orElse(-1);
			milestones.add(new int[]{h, orbs, msg});
		}
	}

	/** 적중 1회. 마일스톤에 도달하면 보상 반환. */
	public Reward hit() {
		hits++;
		sinceHit = 0;
		if (nextMilestone < milestones.size() && hits >= milestones.get(nextMilestone)[0]) {
			int[] m = milestones.get(nextMilestone++);
			return new Reward(m[0], m[1], m[2]);
		}
		return null;
	}

	public void update(float dt) {
		if (hits > 0 && (sinceHit += dt) > resetTime) {
			hits = 0;
			nextMilestone = 0;
		}
	}

	public int hits() {
		return hits;
	}

	public int milestoneCount() {
		return milestones.size();
	}
}
