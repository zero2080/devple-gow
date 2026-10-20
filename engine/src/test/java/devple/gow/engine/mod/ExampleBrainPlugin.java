package devple.gow.engine.mod;

import devple.gow.engine.combat.EnemyFighter;
import org.joml.Vector3f;

/**
 * 예제 플러그인 (49 문서 §13): 미노타우로스 (R_MIN, AI1 050) 를 "대상 쪽으로 걷기만 하는" 제어로 바꾼다.
 * 테스트 자원 META-INF/services 로 등록된다 — {@link Plugins#start} 를 부른 테스트에서만 켜진다.
 */
public final class ExampleBrainPlugin implements GowPlugin {

	/** 등록 열쇠 (WAD 이름). */
	public static final String KEY = "min";

	/** 걷기만 하는 제어 — 불린 횟수를 센다. */
	public static final class Walker implements EnemyController {
		public int calls;

		@Override
		public void update(EnemyFighter self, float dt) {
			calls++;
			Vector3f stick = new Vector3f();
			float face = Float.NaN;
			if (self.foe() != null) {
				stick.set(self.foe().position()).sub(self.pos).setComponent(1, 0);
				if (stick.lengthSquared() > 1f) {
					face = (float) Math.atan2(-stick.x, -stick.z);
					stick.normalize();
				} else {
					stick.zero();
				}
			}
			self.steer(stick, face, dt);
		}
	}

	public static int frames;

	@Override
	public void onStart(PluginContext ctx) {
		ctx.replaceEnemy(KEY, f -> new Walker());
	}

	@Override
	public void onFrame(float dt) {
		frames++;
	}
}
