package devple.gow.engine.scene;

import devple.gow.engine.core.Engine;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;

import java.util.Locale;
import java.util.StringJoiner;

import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.opengl.GL11.*;

/**
 * 게임패드 진단 ({@code --pad-test}): 연결된 조이스틱·매핑 상태를 출력하고, 누른 버튼(원작 PS2 이름)과 스틱 값을 창 제목·콘솔에 보인다.
 * 버튼을 누르면 배경색이 바뀐다. 창을 닫으면 끝.
 */
public final class PadTestScene implements Scene {

	private String last = "";
	private float flash;

	@Override
	public void enter(Engine e) {
		System.out.println("== 게임패드 진단 ==");
		var js = Input.describeJoysticks();
		if (js.isEmpty()) {
			System.out.println("연결된 조이스틱 없음 — 패드를 연결하면 1 초 안에 인식한다");
		}
		js.forEach(d -> System.out.println("  " + d));
		System.out.println("버튼을 눌러 보세요 (원작 배치: L2 마법, R2 사용, R1 대시, ○ 잡기/포세이돈 연타)");
	}

	@Override
	public void update(Engine e, float dt) {
		Input in = e.input();
		StringJoiner held = new StringJoiner(" ");
		for (PadButton b : PadButton.values()) {
			if (in.held(b)) {
				held.add(b.name());
			}
			if (in.pressed(b)) {
				flash = 1;
			}
		}
		String pad = in.padName() != null ? in.padName() : "패드 없음 (키보드)";
		String s = String.format(Locale.ROOT, "%s | %s | L(%.2f, %.2f) R(%.2f, %.2f)", pad, held.length() == 0 ? "-" : held,
				in.leftX(), in.leftY(), in.rightX(), in.rightY());
		if (!s.equals(last)) {
			last = s;
			System.out.println(s);
			glfwSetWindowTitle(e.window().handle(), "패드 진단 — " + s);
		}
		flash = Math.max(0, flash - dt * 3);
	}

	@Override
	public void render(Engine e, float alpha) {
		glClearColor(0.1f + 0.5f * flash, 0.1f, 0.15f, 1);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
	}
}
