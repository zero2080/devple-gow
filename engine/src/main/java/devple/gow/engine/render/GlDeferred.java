package devple.gow.engine.render;

import java.util.ArrayDeque;
import java.util.Collection;

/**
 * 미뤄 둔 GL 해제 (48 문서 §9). 떠난 레벨 장면의 메시·텍스처·셰이더 지우기는 게임에 보이는 효과가 없으므로 씬 교체 프레임에서 하지 않고
 * 다음 틱들에 조금씩 ({@link #drain}, 메인 스레드) 한다. 순서는 넣은 순서 그대로.
 */
public final class GlDeferred {

	private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();

	private GlDeferred() {
	}

	public static void add(Runnable r) {
		QUEUE.add(r);
	}

	public static void addAll(Collection<Runnable> rs) {
		QUEUE.addAll(rs);
	}

	/** budgetNanos 동안 (최소 하나) 실행. */
	public static void drain(long budgetNanos) {
		if (QUEUE.isEmpty()) {
			return;
		}
		long deadline = System.nanoTime() + budgetNanos;
		do {
			Runnable r = QUEUE.poll();
			if (r == null) {
				return;
			}
			r.run();
		} while (System.nanoTime() < deadline);
	}

	/** 남은 것을 모두 (종료·시험). */
	public static void drainAll() {
		drain(Long.MAX_VALUE / 2);
	}

	public static int pending() {
		return QUEUE.size();
	}
}
