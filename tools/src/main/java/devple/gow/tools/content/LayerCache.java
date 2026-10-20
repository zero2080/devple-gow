package devple.gow.tools.content;

import java.util.function.Function;

/**
 * 계층에서 만든 표 하나를 현재 {@link Content} 에 묶어 둔다 (49 문서 §11–§14). 계층이 바뀌면 ({@link Content#install}) 다음 조회 때 다시
 * 만든다 — 조회 비용은 volatile 읽기 두 번이라 프레임마다 불러도 된다.
 */
public final class LayerCache<T> {

	private final Function<Content, T> build;
	private volatile Content owner;
	private volatile T value;

	public LayerCache(Function<Content, T> build) {
		this.build = build;
	}

	public T get() {
		Content c = Content.get();
		if (c != owner) {
			synchronized (this) {
				if (c != owner) {
					value = build.apply(c);
					owner = c;
				}
			}
		}
		return value;
	}
}
