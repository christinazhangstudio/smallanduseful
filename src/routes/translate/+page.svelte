<script lang="ts">
	import { onMount, tick } from 'svelte';
	import { beforeNavigate } from '$app/navigation';
	import { createSelectionMirror, selectionViewport } from '$lib/selection-position';
	import PublicTranslationLibrary from './PublicTranslationLibrary.svelte';

	type Alternative = { text: string; pinyin: string; meaning: string };
	type SelectionSnapshot = { text: string; start: number; end: number };

	const limit = 4000;
	let transcript = $state('');
	let translation = $state('');
	let translationError = $state('');
	let translating = $state(false);
	let translationWaiting = $state(false);
	let composing = $state(false);
	let compositionBase = '';
	let limitMessage = $state('');
	let textarea: HTMLTextAreaElement;

	let disposed = false;

	let translationTimer: ReturnType<typeof setTimeout> | undefined;
	let translationController: AbortController | null = null;
	let textRevision = 0;
	let selectionStart = $state(0);
	let selectionEnd = $state(0);
	let snapshot = $state<SelectionSnapshot | null>(null);
	let alternatives = $state<Alternative[]>([]);
	let phoneticsLoading = $state(false);
	let phoneticsError = $state('');
	let phoneticsFinished = $state(false);
	let phoneticsController: AbortController | null = null;
	let selectionRevision = 0;
	let phoneticsTimer: ReturnType<typeof setTimeout> | undefined;
	let dismissedSelection: SelectionSnapshot | null = null;
	let pointerSelecting = $state(false);
	let restoringSelection = false;
	let popup: HTMLDivElement | undefined;
	let popupPosition = $state<{ left: number; top: number; width: number; maxHeight: number } | null>(null);
	let mirror: ReturnType<typeof createSelectionMirror> | undefined;
	let positionFrame: number | undefined;
	const selectedText = $derived(transcript.slice(selectionStart, selectionEnd));
	const validSelection = $derived(
		!composing && selectedText.trim().length > 0 && selectedText.length <= 24
	);

	beforeNavigate(() => cancelPointerSelection());

	onMount(() => {
		mirror = createSelectionMirror(textarea);
		const editorResize = new ResizeObserver(schedulePosition);
		editorResize.observe(textarea);
		document.addEventListener('selectionchange', updateSelection);
		document.addEventListener('pointerdown', handlePointerDown, true);
		document.addEventListener('pointerup', finishPointerSelection, true);
		document.addEventListener('pointercancel', cancelPointerSelection, true);
		document.addEventListener('keydown', handleEscape, true);
		document.addEventListener('focusin', handleFocus);
		window.addEventListener('scroll', schedulePosition, true);
		window.addEventListener('resize', schedulePosition);
		window.addEventListener('blur', cancelPointerSelection);
		window.addEventListener('pagehide', cancelPointerSelection);
		window.visualViewport?.addEventListener('resize', schedulePosition);
		window.visualViewport?.addEventListener('scroll', schedulePosition);
		return () => {
			disposed = true;
			textRevision += 1;
			clearTimeout(translationTimer);
			translationController?.abort();
			clearPhonetics();
			editorResize.disconnect();
			mirror?.destroy();
			mirror = undefined;
			document.removeEventListener('selectionchange', updateSelection);
			document.removeEventListener('pointerdown', handlePointerDown, true);
			document.removeEventListener('pointerup', finishPointerSelection, true);
			document.removeEventListener('pointercancel', cancelPointerSelection, true);
			document.removeEventListener('keydown', handleEscape, true);
			document.removeEventListener('focusin', handleFocus);
			window.removeEventListener('scroll', schedulePosition, true);
			window.removeEventListener('resize', schedulePosition);
			window.removeEventListener('blur', cancelPointerSelection);
			window.removeEventListener('pagehide', cancelPointerSelection);
			window.visualViewport?.removeEventListener('resize', schedulePosition);
			window.visualViewport?.removeEventListener('scroll', schedulePosition);
		};
	});


	function invalidateTranslation() {
		textRevision += 1;
		clearTimeout(translationTimer);
		translationController?.abort();
		translationController = null;
		translation = '';
		translationError = '';
		translating = false;
		translationWaiting = false;
	}

	function clearPhonetics() {
		selectionRevision += 1;
		clearTimeout(phoneticsTimer);
		phoneticsTimer = undefined;
		if (positionFrame !== undefined) cancelAnimationFrame(positionFrame);
		positionFrame = undefined;
		phoneticsController?.abort();
		phoneticsController = null;
		snapshot = null;
		popupPosition = null;
		alternatives = [];
		phoneticsLoading = false;
		phoneticsError = '';
		phoneticsFinished = false;
	}

	function invalidatePhonetics() {
		dismissedSelection = textarea
			? { text: transcript, start: textarea.selectionStart, end: textarea.selectionEnd }
			: null;
		clearPhonetics();
	}

	function textChanged() {
		invalidateTranslation();
		invalidatePhonetics();
		if (composing || !transcript.trim() || transcript.length > limit) return;
		translationWaiting = true;
		translationTimer = setTimeout(() => void translate(), 600);
	}

	function editTranscript(event: Event) {
		const field = event.currentTarget as HTMLTextAreaElement;
		if (!composing && field.value.length > limit) {
			field.value = transcript;
			limitMessage = 'That edit was not applied: the transcript cannot exceed 4,000 characters. 此次编辑未应用：正文不能超过 4,000 字符。';
			updateSelection();
			return;
		}
		transcript = field.value;
		limitMessage = '';
		textChanged();
		updateSelection();
	}

	function startComposition() {
		compositionBase = transcript;
		composing = true;
		textChanged();
	}

	function endComposition(event: CompositionEvent) {
		if (!composing) return;
		const field = event.currentTarget as HTMLTextAreaElement;
		composing = false;
		if (field.value.length > limit) {
			transcript = compositionBase;
			field.value = transcript;
			limitMessage = 'That composition was not applied: the transcript cannot exceed 4,000 characters. 此次输入未应用：正文不能超过 4,000 字符。';
		} else {
			transcript = field.value;
		}
		textChanged();
		updateSelection();
	}

	function matchesSelection(a: SelectionSnapshot, b: SelectionSnapshot) {
		return a.text === b.text && a.start === b.start && a.end === b.end;
	}

	function updateSelection() {
		if (!textarea || disposed || restoringSelection) return;
		const start = textarea.selectionStart;
		const end = textarea.selectionEnd;
		const current = { text: transcript, start, end };
		if (start !== selectionStart || end !== selectionEnd) {
			selectionStart = start;
			selectionEnd = end;
			if (dismissedSelection && !matchesSelection(current, dismissedSelection)) dismissedSelection = null;
			clearPhonetics();
		}
		if (!validSelection) {
			if (snapshot) clearPhonetics();
			return;
		}
		// Moving focus into the non-modal popup must not clear the editor's range,
		// while selection events from elsewhere must never reopen an old range.
		if (document.activeElement !== textarea && !pointerSelecting) return;
		if (dismissedSelection && matchesSelection(current, dismissedSelection)) return;
		if (!snapshot) {
			snapshot = current;
			phoneticsLoading = true;
			if (!pointerSelecting) scheduleLookup();
			void tick().then(schedulePosition);
		}
	}

	function scheduleLookup() {
		clearTimeout(phoneticsTimer);
		const current = snapshot;
		if (!current || pointerSelecting) return;
		phoneticsTimer = setTimeout(() => {
			phoneticsTimer = undefined;
			if (snapshot === current && !pointerSelecting) void findAlternatives();
		}, 300);
	}

	function handlePointerDown(event: PointerEvent) {
		if (!event.isPrimary || event.button !== 0) return;
		if (event.target === textarea) {
			pointerSelecting = true;
			dismissedSelection = null;
			clearPhonetics();
		} else if (!(event.target instanceof Node) || !popup?.contains(event.target)) {
			invalidatePhonetics();
		}
	}

	function finishPointerSelection() {
		if (!pointerSelecting) return;
		pointerSelecting = false;
		updateSelection();
		scheduleLookup();
	}

	function cancelPointerSelection() {
		pointerSelecting = false;
		invalidatePhonetics();
	}

	function handleFocus(event: FocusEvent) {
		if (event.target !== textarea && (!(event.target instanceof Node) || !popup?.contains(event.target))) {
			invalidatePhonetics();
		}
	}

	function handleEscape(event: KeyboardEvent) {
		if (event.key !== 'Escape' || !snapshot) return;
		event.preventDefault();
		const returnFocus = !!popup?.contains(document.activeElement);
		invalidatePhonetics();
		if (returnFocus) textarea.focus({ preventScroll: true });
	}

	function handleEditorKeydown(event: KeyboardEvent) {
		if (event.key === 'Tab' && !event.shiftKey && snapshot && popup) {
			const first = popup.querySelector<HTMLButtonElement>('button:not(:disabled)');
			if (first) {
				event.preventDefault();
				first.focus({ preventScroll: true });
			}
		}
	}

	function handlePopupKeydown(event: KeyboardEvent) {
		if (event.key === 'Tab' && event.shiftKey && event.target === popup?.querySelector('button:not(:disabled)')) {
			event.preventDefault();
			textarea.focus({ preventScroll: true });
		}
	}

	function preserveEditorSelection(event: PointerEvent) {
		if (event.button === 0 && event.target instanceof Element && event.target.closest('button')) {
			event.preventDefault();
		}
	}

	function mountPopup(node: HTMLDivElement) {
		popup = node;
		const resize = new ResizeObserver(schedulePosition);
		resize.observe(node);
		schedulePosition();
		return {
			destroy() {
				resize.disconnect();
				if (popup === node) popup = undefined;
			}
		};
	}

	function schedulePosition() {
		if (disposed || !snapshot || positionFrame !== undefined) return;
		positionFrame = requestAnimationFrame(() => {
			positionFrame = undefined;
			positionPopup();
		});
	}

	function positionPopup() {
		const current = snapshot;
		if (!current || !popup || !mirror) return;
		if (!sameSelection(current) || composing) {
			invalidatePhonetics();
			return;
		}
		const anchor = mirror.measure(current.start, current.end);
		if (!anchor) {
			invalidatePhonetics();
			return;
		}
		const viewport = selectionViewport();
		const margin = 12;
		const gap = 8;
		const width = Math.min(360, viewport.right - viewport.left - margin * 2);
		const above = Math.max(0, anchor.top - viewport.top - margin - gap);
		const below = Math.max(0, viewport.bottom - anchor.selectionBottom - margin - gap);
		const desiredHeight = Math.min(popup.scrollHeight + 2, 360);
		const placeAbove = above >= desiredHeight || above >= below;
		const maxHeight = Math.min(360, placeAbove ? above : below);
		if (width <= 0 || maxHeight < 48) {
			invalidatePhonetics();
			return;
		}
		popupPosition = {
			left: Math.max(viewport.left + margin, Math.min(
				(anchor.left + anchor.right - width) / 2, viewport.right - margin - width
			)),
			top: placeAbove ? anchor.top - gap - Math.min(desiredHeight, maxHeight) : anchor.selectionBottom + gap,
			width,
			maxHeight
		};
	}

	function isObject(value: unknown): value is Record<string, unknown> {
		return value !== null && typeof value === 'object';
	}

	async function post(path: string, body: object, signal: AbortSignal): Promise<unknown> {
		const response = await fetch(path, {
			method: 'POST',
			headers: { 'content-type': 'application/json' },
			cache: 'no-store',
			body: JSON.stringify(body),
			signal
		});
		let value: unknown;
		try {
			value = await response.json();
		} catch {
			throw new Error('The server returned an unreadable response. Please try again.');
		}
		if (!response.ok) {
			throw new Error(isObject(value) && typeof value.message === 'string'
				? value.message : 'The request failed. Please try again.');
		}
		return value;
	}

	async function translate() {
		clearTimeout(translationTimer);
		if (disposed || composing || !transcript.trim() || transcript.length > limit) return;
		translationController?.abort();
		const controller = new AbortController();
		translationController = controller;
		const revision = textRevision;
		const text = transcript;
		translationWaiting = false;
		translationError = '';
		translating = true;
		try {
			const value = await post('/api/translate', { text }, controller.signal);
			if (!isObject(value) || typeof value.translation !== 'string' || !value.translation.trim()) {
				throw new Error('The server did not return an English translation. Please try again.');
			}
			if (disposed || controller.signal.aborted || revision !== textRevision) return;
			translation = value.translation;
		} catch (error) {
			if (disposed || controller.signal.aborted || revision !== textRevision) return;
			translationError = error instanceof Error ? error.message : 'Translation failed. Please try again.';
		} finally {
			if (!disposed && revision === textRevision && translationController === controller) {
				translating = false;
				translationController = null;
			}
		}
	}

	function sameSelection(current: SelectionSnapshot) {
		return transcript === current.text && selectionStart === current.start && selectionEnd === current.end
			&& textarea.selectionStart === current.start && textarea.selectionEnd === current.end;
	}

	async function findAlternatives() {
		clearTimeout(phoneticsTimer);
		phoneticsTimer = undefined;
		const current = snapshot;
		if (!current || disposed || pointerSelecting || !validSelection || !sameSelection(current)) return;
		phoneticsController?.abort();
		const revision = ++selectionRevision;
		alternatives = [];
		phoneticsError = '';
		phoneticsFinished = false;
		const controller = new AbortController();
		phoneticsController = controller;
		phoneticsLoading = true;
		try {
			const value = await post('/api/phonetics', current, controller.signal);
			if (!isObject(value) || !Array.isArray(value.alternatives) || value.alternatives.length > 6) {
				throw new Error('The server did not return sound-alike alternatives. Please try again.');
			}
			const seen = new Set<string>();
			const original = current.text.slice(current.start, current.end).trim();
			const choices: Alternative[] = value.alternatives.map((item: unknown) => {
				if (!isObject(item) || typeof item.text !== 'string' || !item.text.trim() || item.text.length > 24
					|| typeof item.pinyin !== 'string' || !item.pinyin.trim() || item.pinyin.length > 160
					|| typeof item.meaning !== 'string' || !item.meaning.trim() || item.meaning.length > 240
					|| item.text.trim() === original || seen.has(item.text.trim())) {
					throw new Error('The server returned an invalid alternative. Please try again.');
				}
				seen.add(item.text.trim());
				return { text: item.text, pinyin: item.pinyin, meaning: item.meaning };
			});
			if (disposed || controller.signal.aborted || revision !== selectionRevision || !sameSelection(current)) return;
			alternatives = choices;
			phoneticsFinished = true;
		} catch (error) {
			if (disposed || controller.signal.aborted || revision !== selectionRevision || !sameSelection(current)) return;
			phoneticsError = error instanceof Error ? error.message : 'Could not find alternatives. Please try again.';
		} finally {
			if (!disposed && revision === selectionRevision && phoneticsController === controller) {
				phoneticsLoading = false;
				phoneticsController = null;
			}
		}
	}

	async function replaceSelection(alternative: Alternative) {
		const current = snapshot;
		if (!current || !sameSelection(current) || composing) {
			invalidatePhonetics();
			return;
		}
		const replacement = transcript.slice(0, current.start) + alternative.text + transcript.slice(current.end);
		if (replacement.length > limit) {
			limitMessage = 'This replacement would exceed 4,000 characters and was not applied. 此替换会超出 4,000 字符上限，未应用。';
			return;
		}
		restoringSelection = true;
		transcript = replacement;
		limitMessage = '';
		textChanged();
		await tick();
		if (disposed) {
			restoringSelection = false;
			return;
		}
		selectionStart = current.start;
		selectionEnd = current.start + alternative.text.length;
		dismissedSelection = { text: transcript, start: selectionStart, end: selectionEnd };
		textarea.focus({ preventScroll: true });
		textarea.setSelectionRange(selectionStart, selectionEnd);
		restoringSelection = false;
	}

	function clearTranscript() {
		transcript = '';
		composing = false;
		compositionBase = '';
		limitMessage = '';
		selectionStart = 0;
		selectionEnd = 0;
		textChanged();
		textarea.focus();
	}
</script>

<svelte:head>
	<title>Chinese to English · 中文译英文 — Small Useful Page 小万事页</title>
	<meta name="description" content="Translate typed Chinese with Qwen and explore public conversations published from Fan yi Xia, the offline Android Mandarin translator. 输入中文并使用 Qwen 翻译，浏览翻译一下发布的公共会话。" />
</svelte:head>

<div class="site-shell">
	<main>
		<header class="intro">
			<p class="eyebrow"><span>05 / words, your way</span><span lang="zh-Hans">把话说清楚</span></p>
			<h1><span>Chinese to English</span><span lang="zh-Hans">中文译英文</span></h1>
			<p class="lede">Type, refine, and translate. Revisit conversations shared from your phone.<br /><span lang="zh-Hans">输入、修改、翻译，也可回顾手机上主动发布的会话。</span></p>
		</header>

		<aside class="android-callout" aria-labelledby="android-heading">
			<p class="eyebrow">Android · Offline after setup</p>
			<h2 id="android-heading">Fan yi Xia <span lang="zh-Hans">翻译一下</span></h2>
			<p>Microphone capture now belongs in the separate native Android app, Fan yi Xia. After downloading its models during setup, Mandarin transcription (Whisper base) and English translation (ML Kit) run on your phone without a network connection.</p>
			<p>Nothing is shared automatically. Choose Publish in the app to send only a session’s Chinese and English text to the public library below. Publishing and Qwen summaries need an internet connection; audio is never uploaded.</p>
			<p lang="zh-Hans">录音功能已移至独立 Android 应用 Fan yi Xia。首次下载模型后，普通话转写与英文翻译均可离线运行。不会自动分享；只有主动点击发布，才会将中英文文字发送到下方公共会话库。发布与 Qwen 总结需要联网，音频不会上传。</p>
			<a href="#public-conversations">Browse public conversations <span lang="zh-Hans">浏览公共会话</span> ↓</a>
		</aside>

		<div class="workspace">
			<section class="panel chinese" aria-labelledby="chinese-heading">
				<div class="panel-heading">
					<h2 id="chinese-heading">Chinese <span lang="zh-Hans">中文原文</span></h2>
					<button class="small" type="button" onclick={clearTranscript} disabled={!transcript && !translationError && !limitMessage}>Clear <span lang="zh-Hans">清空</span></button>
				</div>
				<label class="field-label" for="transcript">Editable transcript <span lang="zh-Hans">可编辑的中文</span></label>
				<textarea
					id="transcript"
					bind:this={textarea}
					value={transcript}
					lang="zh-Hans"
					rows="10"
					spellcheck="false"
					placeholder="在这里输入或粘贴中文。"
					aria-describedby="transcript-help transcript-count phonetics-hint"
					aria-haspopup="dialog"
					aria-controls={snapshot ? 'phonetics-popup' : undefined}
					onbeforeinput={invalidatePhonetics}
					oninput={editTranscript}
					oncompositionstart={startComposition}
					oncompositionend={endComposition}
					onselect={updateSelection}
					onkeyup={updateSelection}
					onkeydown={handleEditorKeydown}
				></textarea>
				<div class="field-meta">
					<p id="transcript-help">Edit freely. English follows after a short pause.<br /><span lang="zh-Hans">可直接修改，稍停片刻即更新英文。</span></p>
					<span id="transcript-count" class:at-limit={transcript.length >= limit}>{transcript.length.toLocaleString()} / 4,000</span>
				</div>
				<p id="phonetics-hint" class="phonetics-hint">Highlight a phrase (up to 24 characters) for sound-alike choices. Choose one to replace; Escape closes.<br /><span lang="zh-Hans">选中词句（最多 24 字符）即可查看近音词；选择后才替换，Esc 关闭。</span></p>
				{#if limitMessage}
					<div class="notice error" role="alert">
						<p>{limitMessage}</p>
					</div>
				{/if}
			</section>

			<section class="panel english" aria-labelledby="english-heading" aria-busy={translating}>
				<div class="panel-heading"><h2 id="english-heading">English <span lang="zh-Hans">英文翻译</span></h2><span class="engine">Qwen</span></div>
				<p class="translation-status" role="status">
					{#if composing}Waiting for Chinese input to finish · 等待中文输入完成
					{:else if translating}Translating… 正在翻译…
					{:else if translationWaiting}Updating after your pause… 稍停后更新…
					{:else if translationError}Translation unavailable · 翻译暂不可用
					{:else if translation}Up to date · 已更新
					{:else}Your English will appear here · 英文将在这里显示{/if}
				</p>
				{#if translation}<div class="translation" lang="en">{translation}</div>
				{:else if translationError}
					<div class="notice error" role="alert"><p>{translationError}</p><button type="button" onclick={() => void translate()} disabled={composing || !transcript.trim()}>Retry translation <span lang="zh-Hans">重试翻译</span></button></div>
				{:else}<div class="empty-translation" aria-hidden="true"><span>中文</span><span class="arrow">→</span><span>English</span></div>{/if}
			</section>
		</div>

		<aside class="privacy" aria-label="Privacy and limits">
			<p><strong>Typed text stays separate from the public library.</strong> Chinese text and selected phrases go to the server’s Qwen for translation and sound-alike suggestions. This editor does not save or publish your text. Public sessions below are explicitly published from Fan yi Xia; their saved translations are not replaced by Qwen analysis.</p>
			<p lang="zh-Hans">输入框与公共会话库互相独立。中文原文及选中词句会发送到服务器上的 Qwen，用于翻译和近音建议。此编辑器不会保存或发布文字。下方会话由 Fan yi Xia 用户主动发布，Qwen 分析不会覆盖已保存的翻译。</p>
			<p class="limits">4,000 UTF-16 characters per typed text · 24 per selected phrase · AI suggestions and translations can be wrong; review before using.<br /><span lang="zh-Hans">输入上限 4,000 个 UTF-16 字符 · 选中词句上限 24 个 · AI 翻译及建议可能有误，使用前请核对。</span></p>
		</aside>

		<PublicTranslationLibrary />
	</main>
</div>

{#if snapshot}
	<div
		id="phonetics-popup"
		class="phonetics-popup"
		role="dialog"
		aria-modal="false"
		aria-labelledby="phonetics-heading"
		aria-describedby="phonetics-popup-help"
		aria-busy={phoneticsLoading}
		tabindex="-1"
		use:mountPopup
		style:visibility={popupPosition ? 'visible' : 'hidden'}
		style:left={`${popupPosition?.left ?? 0}px`}
		style:top={`${popupPosition?.top ?? 0}px`}
		style:width={`${popupPosition?.width ?? 360}px`}
		style:max-height={`${popupPosition?.maxHeight ?? 360}px`}
		onpointerdown={preserveEditorSelection}
		onkeydown={handlePopupKeydown}
	>
		<div class="popup-heading">
			<h2 id="phonetics-heading">Similar sounds <span lang="zh-Hans">近音词</span></h2>
			<button class="small" type="button" onclick={() => {
				const returnFocus = !!popup?.contains(document.activeElement);
				invalidatePhonetics();
				if (returnFocus) textarea.focus({ preventScroll: true });
			}} aria-label="Close suggestions · 关闭近音建议">×</button>
		</div>
		<p class="selection-preview">Selected · 已选：<mark lang="zh-Hans">{snapshot.text.slice(snapshot.start, snapshot.end)}</mark></p>
		<p id="phonetics-popup-help" class="popup-help">Choose to replace · 选择后才替换</p>
		<div role="status" class="suggestion-status">
			{#if pointerSelecting}Release the selection to look up… 选好后松开即可查找…
			{:else if phoneticsLoading}Finding sound-alike phrases… 正在查找近音词…
			{:else if phoneticsFinished && alternatives.length === 0}No alternatives found for this phrase. 未找到此词句的近音替代。{/if}
		</div>
		{#if phoneticsError}
			<div class="notice error" role="alert">
				<p>{phoneticsError}</p>
				<button type="button" onclick={() => void findAlternatives()}>Retry <span lang="zh-Hans">重试</span></button>
			</div>
		{/if}
		{#if alternatives.length > 0}
			<ul class="alternatives">
				{#each alternatives as alternative}
					<li><button type="button" onclick={() => void replaceSelection(alternative)}><span class="alternative-text" lang="zh-Hans">{alternative.text}</span><span class="pinyin" lang="zh-Latn-pinyin">{alternative.pinyin}</span><span class="meaning">{alternative.meaning}</span><span class="replace-label">Replace · 替换</span></button></li>
				{/each}
			</ul>
		{/if}
	</div>
{/if}

<style>
	.site-shell { width: min(100%, 1160px); min-height: 100%; margin: 0 auto; padding-inline: clamp(16px, 3vw, 40px); }
	main { min-width: 0; padding: clamp(28px, 5vw, 56px) 0 40px; }
	.intro { margin-bottom: 28px; }
	.eyebrow { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 12px; color: var(--accent-dark); font-size: 12px; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; }
	h1 { display: flex; flex-wrap: wrap; align-items: baseline; gap: 12px; margin: 0; font-size: clamp(36px, 4.6vw + 1rem, 66px); font-weight: 500; letter-spacing: -0.06em; line-height: 1.02; }
	h1 [lang='zh-Hans'] { font-size: 0.29em; }
	.lede { margin: 18px 0 0; color: var(--muted); font-size: 15px; line-height: 1.7; }
	h2 { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px; margin: 0; font-size: 16px; font-weight: 600; letter-spacing: -0.02em; }
	button { display: inline-flex; align-items: baseline; justify-content: center; flex-wrap: wrap; gap: 6px; min-height: 42px; padding: 10px 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--paper); color: var(--ink); font-size: 13px; font-weight: 600; cursor: pointer; }
	button:hover:not(:disabled) { border-color: var(--accent-dark); background: color-mix(in srgb, var(--paper), white 12%); }
	button:disabled { opacity: 0.5; cursor: not-allowed; }
	button.small { min-height: 34px; padding: 7px 10px; font-size: 11px; }
	.android-callout { padding: 20px; border: 1px solid var(--line); border-radius: 16px; background: #dde2ce; }
	.android-callout p:not(.eyebrow) { max-width: 85ch; margin: 10px 0; font-size: 13px; line-height: 1.8; }
	.android-callout a { display: inline-block; margin-top: 6px; color: var(--accent-dark); font-size: 13px; font-weight: 600; text-underline-offset: 3px; }
	.workspace { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); margin-top: 18px; border: 1px solid var(--line); border-radius: 16px; overflow: hidden; }
	.panel { min-width: 0; padding: clamp(16px, 2.5vw, 24px); background: var(--paper); }
	.english { border-left: 1px solid var(--line); background: #ede1ce; }
	.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 34px; margin-bottom: 18px; }
	.engine { padding: 4px 8px; border: 1px solid var(--line); border-radius: 6px; color: var(--muted); font-size: 10px; letter-spacing: 0.06em; }
	.field-label { display: flex; flex-wrap: wrap; align-items: baseline; gap: 7px; margin-bottom: 8px; color: var(--muted); font-size: 12px; }
	textarea { display: block; width: 100%; min-width: 0; min-height: 270px; padding: 14px; border: 1px solid var(--line); border-radius: 10px; resize: vertical; background: #f2e8d9; color: var(--ink); font-size: 18px; font-weight: 400; line-height: 1.8; letter-spacing: 0; }
	textarea::placeholder { color: #897d6f; }
	textarea:focus-visible { outline: 2px solid var(--accent-dark); outline-offset: 2px; }
	.field-meta { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: baseline; gap: 8px; margin-top: 10px; color: var(--muted); font-size: 11px; line-height: 1.6; }
	.field-meta p { margin: 0; }
	#transcript-count { white-space: nowrap; font-variant-numeric: tabular-nums; }
	.at-limit { color: #853f32; font-weight: 600; }
	.translation-status { min-height: 20px; margin: 0 0 14px; color: var(--muted); font-size: 12px; line-height: 1.6; }
	.translation { font-size: 18px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
	.empty-translation { display: flex; flex-wrap: wrap; justify-content: center; align-items: center; gap: 14px; min-height: 240px; color: #91816d; font-size: 20px; }
	.arrow { color: var(--accent); font-size: 28px; }
	.notice { margin: 14px 0 0; padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; background: #eee2cc; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
	.notice p { margin: 0 0 8px; }
	.notice p:last-child { margin-bottom: 0; }
	.notice.error { border-color: #b68d78; background: #ecd9cc; color: #743e30; }
	.phonetics-hint { margin: 10px 0 0; color: var(--muted); font-size: 11px; line-height: 1.7; }
	.phonetics-popup { position: fixed; z-index: 1000; box-sizing: border-box; max-width: calc(100vw - 24px); overflow-y: auto; overscroll-behavior: contain; padding: 14px; border: 1px solid var(--accent-dark); border-radius: 12px; background: var(--paper); color: var(--ink); box-shadow: 0 8px 32px #382e282e; }
	.popup-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
	.popup-heading h2 { font-size: 14px; }
	.popup-heading button { flex: 0 0 auto; font-size: 20px; line-height: 1; }
	.selection-preview { margin: 10px 0 0; font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
	.popup-help { margin: 5px 0 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
	mark { padding: 3px 5px; border-radius: 4px; background: #edce9e; color: var(--ink); font-size: 15px; white-space: pre-wrap; }
	.suggestion-status { margin-top: 12px; color: var(--muted); font-size: 12px; }
	.suggestion-status:empty { display: none; }
	.alternatives { display: grid; gap: 8px; margin: 12px 0 0; padding: 0; list-style: none; }
	.alternatives li { min-width: 0; }
	.alternatives button { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: baseline; justify-content: stretch; gap: 3px 8px; width: 100%; text-align: left; overflow-wrap: anywhere; }
	.alternative-text { grid-column: 1; grid-row: 1; color: var(--ink); font-size: 20px; line-height: 1.5; }
	.pinyin { grid-column: 1; grid-row: 2; color: var(--accent-dark); font-size: 13px; font-weight: 500; }
	.meaning { grid-column: 1; grid-row: 3; font-size: 12px; font-weight: 400; line-height: 1.5; }
	.replace-label { grid-column: 2; grid-row: 1 / span 3; align-self: center; color: var(--muted); font-size: 10px; }
	.privacy { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--line); color: var(--muted); font-size: 11px; line-height: 1.8; }
	.privacy p { margin: 0 0 8px; }
	.privacy > [lang='zh-Hans'] { font-size: 10px; line-height: 1.8; }
	.limits { margin-top: 14px !important; }
	@media (max-width: 760px) {
		.workspace { grid-template-columns: minmax(0, 1fr); }
		.english { border-left: 0; border-top: 1px solid var(--line); }
		.empty-translation { min-height: 120px; }
		.android-callout { padding: 16px; }
	}
</style>
