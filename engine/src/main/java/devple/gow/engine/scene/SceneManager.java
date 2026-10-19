package devple.gow.engine.scene;

import devple.gow.engine.core.Engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 씬 스택. push = 오버레이(일시정지/업그레이드 메뉴), replace = 씬 이동(레벨 전환).
 * 씬 전환은 원작처럼 페이드 아웃 → 교체 → 페이드 인 으로 처리한다.
 */
public final class SceneManager {

	private static final float FADE_TIME = 0.4f;

	private final List<Scene> stack = new ArrayList<>();
	private Scene pending;
	private float fade;
	private int fadeDir;

	public void push(Engine e, Scene s) {
		stack.add(s);
		s.enter(e);
	}

	public void pop(Engine e) {
		if (!stack.isEmpty()) {
			stack.removeLast().exit(e);
		}
	}

	/** 페이드를 거쳐 최상위 씬을 교체한다. */
	public void transitionTo(Scene s) {
		pending = s;
		fadeDir = 1;
	}

	public Scene top() {
		return stack.isEmpty() ? null : stack.getLast();
	}

	public void update(Engine e, float dt) {
		if (fadeDir != 0) {
			fade += fadeDir * dt / FADE_TIME;
			if (fade >= 1f && pending != null) {
				fade = 1f;
				pop(e);
				push(e, pending);
				pending = null;
				fadeDir = -1;
			} else if (fade <= 0f) {
				fade = 0f;
				fadeDir = 0;
			}
			if (pending != null) {
				return;
			}
		}
		Scene top = top();
		if (top != null) {
			top.update(e, dt);
		}
	}

	public void render(Engine e, float alpha) {
		int first = stack.size() - 1;
		while (first > 0 && stack.get(first).transparent()) {
			first--;
		}
		for (int i = Math.max(first, 0); i < stack.size(); i++) {
			stack.get(i).render(e, alpha);
		}
	}

	/** 0 = 투명, 1 = 완전 검정. */
	public float fade() {
		return fade;
	}
}
