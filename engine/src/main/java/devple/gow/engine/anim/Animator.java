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
	/** 재생 시작 순번 (같은 클립 재시작도 증가) — 루트 모션 프레임 카운터 리셋 (원작 0x1F7408 이 새 애니에서 +0xC8 = 0). */
	private int playSerial;

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
		playSerial++;
		fadeTime = tween(a, 0.15f);
		fade = previous == null || fadeTime <= 0 ? 1f : 0f;
	}

	/** 클립 객체로 직접 재생 (전투 기술: 시작 위치 = startPhase × 길이, 속도 = 기술 speed). */
	public void playClip(Animation a, float startTime, float rate) {
		previous = current;
		prevTime = time;
		current = a;
		time = startTime;
		playSerial++;
		this.rate = rate;
		fadeTime = tween(a, 0.1f);
		fade = previous == null || fadeTime <= 0 ? 1f : 0f;
	}

	/**
	 * 전환 시간: TWK {@code <클립>_Tween} 이 있으면 그 값 (0x116BB0 이 act +0x04 를 덮어씀), 없으면 act +0x04 (영웅 처형 애니 0.2 — 적 WAD 의
	 * 영웅용 애니에는 TWK 가 없다). act 값이 1 초를 넘으면 (적 act 2.0 / 5.0 — 단위 미확인) 기본값 [근사].
	 */
	private float tween(Animation a, float def) {
		float act = a.unk04 >= 0 && a.unk04 <= 1f ? a.unk04 : def;
		return tweaks.f(twkNode, a.name + "_Tween", act);
	}

	/** 현재 클립 시간 지정. */
	public void seek(float t) {
		time = t;
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

	/** 재생 시작 순번 ({@link #play}/{@link #playClip} 마다 증가). */
	public int playSerial() {
		return playSerial;
	}

	/** 한 번 재생 클립이 끝났는지. */
	public boolean finished() {
		return current != null && !current.looping() && time >= current.duration;
	}

	/**
	 * 지금 자세. 같은 틱 안에서 여러 번 불린다 (그리기·관절 조회·밀기 상자·히트 볼륨·이펙트 부착 — 관절마다) → 마지막 결과를 재생 상태
	 * (클립·시간·페이드) 가 같으면 그대로 돌려준다 (48 문서 §3: 프레임 할당 1 위). 돌려준 Pose 는 읽기 전용으로 쓴다.
	 */
	public Pose pose() {
		if (memo != null && memoCur == current && memoPrev == previous && memoTime == time && memoPrevTime == prevTime && memoFade == fade) {
			return memo;
		}
		Pose cur = current.sample(skeleton, time, current.looping());
		Pose out = fade >= 1f || previous == null ? cur
				: PoseBlend.lerp(skeleton, previous.sample(skeleton, prevTime, previous.looping()), cur, fade);
		memo = out;
		memoCur = current;
		memoPrev = previous;
		memoTime = time;
		memoPrevTime = prevTime;
		memoFade = fade;
		return out;
	}

	private Pose memo;
	private Animation memoCur, memoPrev;
	private float memoTime, memoPrevTime, memoFade;

	public Skeleton skeleton() {
		return skeleton;
	}
}
