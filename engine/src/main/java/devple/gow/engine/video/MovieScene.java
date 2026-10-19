package devple.gow.engine.video;

import devple.gow.engine.core.Engine;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.scene.Scene;
import devple.gow.tools.video.VideoFrame;

import java.io.IOException;

/**
 * 컷신 씬 (33 문서). 씬 스택 위에 push 되어 동영상을 재생하고, 끝나거나 START/× 로 건너뛰면 스스로 pop 한 뒤 onDone 을 부른다.
 * 아래 씬은 그리지도 갱신하지도 않는다 (SceneManager 는 최상위만 update).
 * <pre>
 * GameScene 연결 예 (ScriptListener.movie):
 *   if (op == 0) MovieScene.play(engine, name, () -&gt; ...스크립트에 종료 알림...);
 * </pre>
 */
public final class MovieScene implements Scene {

	private final String name;
	private final boolean widescreen;
	private final Runnable onDone;
	private MoviePlayer player;
	private MovieRenderer renderer;
	private boolean done;
	private boolean popSelf;
	private VideoFrame lastShown;
	private int shown;
	private boolean skippable = true;
	private boolean skipped;

	/** 이미 연 재생기로 씬을 만든다 (popSelf = 끝나면 스택에서 스스로 내림). */
	public MovieScene(MoviePlayer player, boolean popSelf, Runnable onDone) {
		this.name = player.name();
		this.widescreen = false;
		this.player = player;
		this.popSelf = popSelf;
		this.onDone = onDone;
	}

	private MovieScene(String name, boolean widescreen, Runnable onDone) {
		this.name = name;
		this.widescreen = widescreen;
		this.popSelf = true;
		this.onDone = onDone;
	}

	/**
	 * 게임 쪽 훅: 현재 씬 위에 동영상을 띄운다. 파일을 못 찾으면 바로 onDone (로그만 남김).
	 * name = 스크립트 PlayMovie 인자 그대로 ("a01Mp03", "Semo02" ...).
	 */
	public static MovieScene play(Engine engine, String name, Runnable onDone) {
		return play(engine, name, false, onDone);
	}

	public static MovieScene play(Engine engine, String name, boolean widescreen, Runnable onDone) {
		MovieScene s = new MovieScene(name, widescreen, onDone);
		engine.scenes().push(engine, s);
		return s;
	}

	/** START/× 로 건너뛰었는지 (onDone 안에서 확인 — 보너스 메뉴 연속 재생 중단 조건). */
	public boolean skipped() {
		return skipped;
	}

	/** false 면 START/× 로 건너뛸 수 없다 (원작의 첫 시청 강제 등). */
	public MovieScene skippable(boolean s) {
		skippable = s;
		return this;
	}

	@Override
	public void enter(Engine engine) {
		if (player == null) {
			try {
				player = MoviePlayer.open(name, widescreen);
			} catch (IOException e) {
				System.err.println("[movie] " + name + " 열기 실패: " + e.getMessage());
			}
			if (player == null) {
				System.err.println("[movie] " + name + " 없음 (gow-rom/*.PSS 또는 P2_gow_kor.iso 필요) — 건너뜀");
			}
		}
		renderer = new MovieRenderer();
	}

	@Override
	public void update(Engine engine, float dt) {
		if (done) {
			return;
		}
		if (player == null) {
			finish(engine);
			return;
		}
		player.update(dt);
		if (skippable && (engine.input().pressed(PadButton.START) || engine.input().pressed(PadButton.CROSS))) {
			player.skip();
			skipped = true;
		}
		if (player.finished()) {
			if (player.error() != null) {
				System.err.println("[movie] " + name + " 디코딩 오류: " + player.error());
			}
			finish(engine);
		}
	}

	private void finish(Engine engine) {
		done = true;
		if (popSelf && engine.scenes().top() == this) {
			engine.scenes().pop(engine);
		} else {
			release();
		}
		if (onDone != null) {
			onDone.run();
		} else if (!popSelf) {
			engine.window().requestClose();   // --movie 단독 실행
		}
	}

	@Override
	public void render(Engine engine, float alpha) {
		if (renderer == null) {
			return;
		}
		VideoFrame f = player == null ? null : player.frame();
		if (f != null && f != lastShown) {
			lastShown = f;
			shown++;
		}
		renderer.draw(f, engine.window().width(), engine.window().height(),
				player == null ? 4f / 3f : player.displayAspect(), 1f);
	}

	@Override
	public void exit(Engine engine) {
		release();
	}

	private void release() {
		if (player != null) {
			if (Boolean.getBoolean("gow.debug") || System.getProperty("gow.movie.stats") != null) {
				System.out.printf("[movie] %s: 표시 %d 프레임, 드롭 %d, 재생 위치 %.2fs, 경과 %.2fs%n",
						player.name(), shown, player.dropped(), player.time(), player.wallSeconds());
			}
			player.close();
			player = null;
		}
		if (renderer != null) {
			renderer.close();
			renderer = null;
		}
	}

	public MoviePlayer player() {
		return player;
	}
}
