package devple.gow.engine.anim;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Pose;
import devple.gow.tools.anim.Skeleton;

/**
 * 클립 재생 + 크로스페이드. 전환 시간은 원작 TWK {@code /Animation/<오브젝트>/<클립>_Tween} (초).
 */
public final class Animator {

	private final Skeleton skeleton;
	private final AnimationSet set;
	private final Tweaks tweaks;
	private final String twkNode;
	private Animation current;
	private Animation previous;
	private float time;
	private float prevTime;
	private float fade = 1f;
	private float fadeTime;
	private float rate = 1f;

	/** @param twkNode 예: "/Animation/goHero/" */
	public Animator(Skeleton skeleton, AnimationSet set, Tweaks tweaks, String twkNode) {
		this.skeleton = skeleton;
		this.set = set;
		this.tweaks = tweaks;
		this.twkNode = twkNode;
	}

	public Animation find(String name) {
		return set.find(name);
	}

	/** 클립 전환 (이미 재생 중이면 무시, restart 면 처음부터). */
	public void play(String name, boolean restart) {
		Animation a = set.find(name);
		if (a == null || (a == current && !restart)) {
			return;
		}
		previous = current;
		prevTime = time;
		current = a;
		time = 0;
		fadeTime = tweaks.f(twkNode, name + "_Tween", 0.15f);
		fade = previous == null || fadeTime <= 0 ? 1f : 0f;
	}

	public void setRate(float r) {
		rate = r;
	}

	public void update(float dt) {
		time += dt * rate;
		prevTime += dt * rate;
		if (fade < 1f) {
			fade = Math.min(1f, fade + dt / fadeTime);
		}
	}

	public Animation current() {
		return current;
	}

	public float time() {
		return time;
	}

	/** 한 번 재생 클립이 끝났는지. */
	public boolean finished() {
		return current != null && !current.looping() && time >= current.duration;
	}

	public Pose pose() {
		Pose cur = current.sample(skeleton, time, current.looping());
		if (fade >= 1f || previous == null) {
			return cur;
		}
		Pose prev = previous.sample(skeleton, prevTime, previous.looping());
		return PoseBlend.lerp(skeleton, prev, cur, fade);
	}

	public Skeleton skeleton() {
		return skeleton;
	}
}
