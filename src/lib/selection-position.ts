export type SelectionRect = { left: number; top: number; right: number; bottom: number };

export function selectionViewport(): SelectionRect {
	const viewport = window.visualViewport;
	const left = viewport?.offsetLeft ?? 0;
	const top = viewport?.offsetTop ?? 0;
	return {
		left,
		top,
		right: left + (viewport?.width ?? document.documentElement.clientWidth),
		bottom: top + (viewport?.height ?? document.documentElement.clientHeight)
	};
}

// A textarea has no DOM selection rectangles. Keep the same text layout in an
// invisible mirror, then map its Range rectangles into the scrolled editor.
export function createSelectionMirror(textarea: HTMLTextAreaElement) {
	const mirror = document.createElement('div');
	mirror.setAttribute('aria-hidden', 'true');
	mirror.style.cssText = 'position:fixed;left:-100000px;top:0;visibility:hidden;pointer-events:none;margin:0;height:auto;min-height:0;max-height:none;overflow:visible;box-sizing:border-box;';
	document.body.append(mirror);
	const text = document.createTextNode('');
	mirror.append(text);
	const range = document.createRange();
	const properties = [
		'font-family', 'font-size', 'font-weight', 'font-style', 'font-stretch',
		'font-variant', 'font-feature-settings', 'font-variation-settings', 'font-kerning',
		'line-height', 'letter-spacing', 'word-spacing', 'text-transform', 'text-indent',
		'text-align', 'text-rendering', 'direction', 'tab-size', 'word-break',
		'padding-top', 'padding-right', 'padding-bottom', 'padding-left',
		'border-top-width', 'border-right-width', 'border-bottom-width', 'border-left-width',
		'border-top-style', 'border-right-style', 'border-bottom-style', 'border-left-style'
	];

	return {
		measure(start: number, end: number): (SelectionRect & { selectionBottom: number }) | null {
			if (start < 0 || end <= start || end > textarea.value.length) return null;
			const style = getComputedStyle(textarea);
			for (const property of properties) mirror.style.setProperty(property, style.getPropertyValue(property));
			const editor = textarea.getBoundingClientRect();
			const borders = parseFloat(style.borderLeftWidth) + parseFloat(style.borderRightWidth);
			const scrollbar = Math.max(0, textarea.offsetWidth - textarea.clientWidth - borders);
			// Preserve fractional CSS widths while excluding the native scrollbar.
			mirror.style.width = `${editor.width - scrollbar}px`;
			mirror.style.whiteSpace = textarea.wrap === 'off' ? 'pre' : 'pre-wrap';
			mirror.style.overflowWrap = textarea.wrap === 'off' ? 'normal' : 'break-word';
			text.data = textarea.value + '\u200b';
			range.setStart(text, start);
			range.setEnd(text, end);
			const origin = mirror.getBoundingClientRect();
			const viewport = selectionViewport();
			const visible = {
				left: Math.max(editor.left + textarea.clientLeft, viewport.left),
				top: Math.max(editor.top + textarea.clientTop, viewport.top),
				right: Math.min(editor.left + textarea.clientLeft + textarea.clientWidth, viewport.right),
				bottom: Math.min(editor.top + textarea.clientTop + textarea.clientHeight, viewport.bottom)
			};
			if (visible.right <= visible.left || visible.bottom <= visible.top) return null;
			let first: SelectionRect | null = null;
			let selectionBottom = 0;
			for (const rect of range.getClientRects()) {
				const left = editor.left + rect.left - origin.left - textarea.scrollLeft;
				const top = editor.top + rect.top - origin.top - textarea.scrollTop;
				const clipped = {
					left: Math.max(left, visible.left),
					top: Math.max(top, visible.top),
					right: Math.min(left + rect.width, visible.right),
					bottom: Math.min(top + rect.height, visible.bottom)
				};
				if (clipped.right > clipped.left && clipped.bottom > clipped.top) {
					first ??= clipped;
					selectionBottom = Math.max(selectionBottom, clipped.bottom);
				}
			}
			return first ? { ...first, selectionBottom } : null;
		},
		destroy() {
			range.detach();
			mirror.remove();
		}
	};
}
