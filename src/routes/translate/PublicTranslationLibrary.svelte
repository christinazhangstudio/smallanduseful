<script lang="ts">
	import { onMount } from 'svelte';

	type Session = {
		id: string;
		title: string;
		chinese: string;
		english: string;
		recordedAt: string;
		publishedAt: string;
		transcriptionEngine: 'whisper-base';
		translationEngine: 'ml-kit';
	};
	type Analysis = {
		id: string;
		mode: 'summary' | 'analysis';
		instruction: string;
		text: string;
		createdAt: string;
	};

	let sessions = $state<Session[]>([]);
	let listReady = $state(false);
	let listLoading = $state(false);
	let listError = $state('');
	let retryRefresh = true;
	let page = -1;
	let hasMore = $state(false);
	let selectedId = $state('');
	let session = $state<Session | null>(null);
	let analyses = $state<Analysis[]>([]);
	let detailLoading = $state(false);
	let detailError = $state('');
	let instruction = $state('');
	let analysisLoading = $state<'summary' | 'analysis' | null>(null);
	let analysisError = $state('');
	let analysisNotice = $state('');
	let listController: AbortController | null = null;
	let detailController: AbortController | null = null;
	let analysisController: AbortController | null = null;
	let disposed = false;

	onMount(() => {
		void loadSessions(true);
		return () => {
			disposed = true;
			listController?.abort();
			detailController?.abort();
			analysisController?.abort();
		};
	});

	function isObject(value: unknown): value is Record<string, unknown> {
		return value !== null && typeof value === 'object';
	}

	function isDate(value: unknown): value is string {
		return typeof value === 'string' && Number.isFinite(Date.parse(value));
	}

	function isSession(value: unknown): value is Session {
		return isObject(value) && typeof value.id === 'string' && !!value.id
			&& typeof value.title === 'string' && !!value.title.trim()
			&& typeof value.chinese === 'string' && typeof value.english === 'string'
			&& isDate(value.recordedAt) && isDate(value.publishedAt)
			&& value.transcriptionEngine === 'whisper-base' && value.translationEngine === 'ml-kit';
	}

	function isAnalysis(value: unknown): value is Analysis {
		return isObject(value) && typeof value.id === 'string' && !!value.id
			&& (value.mode === 'summary' || value.mode === 'analysis')
			&& typeof value.instruction === 'string' && typeof value.text === 'string'
			&& !!value.text.trim() && isDate(value.createdAt);
	}

	async function request(path: string, controller: AbortController, body?: object): Promise<unknown> {
		const response = await fetch(path, {
			method: body ? 'POST' : 'GET',
			cache: 'no-store',
			headers: body ? { 'content-type': 'application/json' } : undefined,
			body: body ? JSON.stringify(body) : undefined,
			signal: controller.signal
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

	function dateLabel(value: string) {
		return new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
	}

	async function loadSessions(refresh: boolean) {
		if (disposed || (!refresh && (!hasMore || listLoading))) return;
		listController?.abort();
		const controller = new AbortController();
		listController = controller;
		const nextPage = refresh ? 0 : page + 1;
		listLoading = true;
		listError = '';
		retryRefresh = refresh;
		if (refresh && selectedId) void selectSession(selectedId, true);
		try {
			const value = await request(`/api/translation-sessions?page=${nextPage}`, controller);
			if (!isObject(value) || !Array.isArray(value.sessions) || !value.sessions.every(isSession)
				|| typeof value.hasMore !== 'boolean') {
				throw new Error('The server did not return a valid conversation list. Please try again.');
			}
			if (disposed || controller.signal.aborted || listController !== controller) return;
			const incoming: Session[] = value.sessions;
			const seen = new Set(refresh ? [] : sessions.map((item) => item.id));
			const unique = incoming.filter((item) => {
				if (seen.has(item.id)) return false;
				seen.add(item.id);
				return true;
			});
			sessions = refresh ? unique : [...sessions, ...unique];
			page = nextPage;
			hasMore = value.hasMore;
			listReady = true;
		} catch (error) {
			if (disposed || controller.signal.aborted || listController !== controller) return;
			listError = error instanceof Error ? error.message : 'Could not load conversations. Please try again.';
		} finally {
			if (!disposed && listController === controller) {
				listLoading = false;
				listController = null;
			}
		}
	}

	async function selectSession(id: string, preserve = false) {
		if (disposed) return;
		detailController?.abort();
		const interruptedAnalysis = !!analysisController;
		analysisController?.abort();
		analysisController = null;
		analysisLoading = null;
		analysisError = '';
		analysisNotice = preserve && interruptedAnalysis
			? 'The previous analysis request was interrupted. It may still be saved on the server; refresh the history before rerunning it.' : '';
		const keepDetail = preserve && selectedId === id;
		selectedId = id;
		if (!keepDetail) {
			session = null;
			analyses = [];
			instruction = '';
		}
		const controller = new AbortController();
		detailController = controller;
		detailLoading = true;
		detailError = '';
		try {
			const value = await request(`/api/translation-sessions/${encodeURIComponent(id)}`, controller);
			if (!isObject(value) || !isSession(value.session) || value.session.id !== id
				|| !Array.isArray(value.analyses) || !value.analyses.every(isAnalysis)) {
				throw new Error('The server did not return a valid conversation. Please try again.');
			}
			if (disposed || controller.signal.aborted || detailController !== controller || selectedId !== id) return;
			session = value.session;
			analyses = value.analyses;
		} catch (error) {
			if (disposed || controller.signal.aborted || detailController !== controller || selectedId !== id) return;
			detailError = error instanceof Error ? error.message : 'Could not load this conversation. Please try again.';
		} finally {
			if (!disposed && detailController === controller) {
				detailLoading = false;
				detailController = null;
			}
		}
	}

	async function runAnalysis(mode: Analysis['mode'], prompt = instruction) {
		if (disposed || !session || detailLoading || analysisLoading) return;
		if (mode === 'analysis' && (!prompt.trim() || prompt.length > 2000)) {
			analysisError = 'Enter an instruction of 1–2,000 characters before running reanalysis.';
			return;
		}
		const id = session.id;
		const controller = new AbortController();
		analysisController = controller;
		analysisLoading = mode;
		analysisError = '';
		analysisNotice = '';
		try {
			const value = await request(`/api/translation-sessions/${encodeURIComponent(id)}/analysis`, controller,
				mode === 'summary' ? { mode } : { mode, instruction: prompt });
			if (!isObject(value) || !isAnalysis(value.analysis) || value.analysis.mode !== mode) {
				throw new Error('The server did not return a saved analysis. Refresh history before trying again.');
			}
			if (disposed || controller.signal.aborted || analysisController !== controller || selectedId !== id) return;
			const saved = value.analysis;
			analyses = [saved, ...analyses.filter((item) => item.id !== saved.id)].slice(0, 50);
			analysisNotice = `${mode === 'summary' ? 'Summary' : 'Reanalysis'} saved. The original translation is unchanged.`;
		} catch (error) {
			if (disposed || controller.signal.aborted || analysisController !== controller || selectedId !== id) return;
			analysisError = error instanceof Error ? error.message : 'Qwen analysis failed. Please try again.';
		} finally {
			if (!disposed && analysisController === controller) {
				analysisLoading = null;
				analysisController = null;
			}
		}
	}
</script>

<section id="public-conversations" class="library" aria-labelledby="library-heading">
	<div class="section-heading">
		<div>
			<p class="eyebrow">Shared from Fan yi Xia · 翻译一下</p>
			<h2 id="library-heading">Public conversations <span lang="zh-Hans">公共会话库</span></h2>
		</div>
		<button type="button" onclick={() => void loadSessions(true)} disabled={listLoading}>Refresh library <span lang="zh-Hans">刷新</span></button>
	</div>
	<p class="public-warning"><strong>Public, not private.</strong> Anyone with access to this site can read published Chinese, English, and saved Qwen analyses. Only publish words you are comfortable sharing; do not publish sensitive conversations. Qwen analysis runs online on the saved English, not on audio.<br /><span lang="zh-Hans">此处不是私人记录：任何访问本站的人都能阅读已发布的中英文及 Qwen 分析。请勿发布敏感会话。Qwen 仅在线分析已保存的英文，不处理音频。</span></p>
	<div class="library-layout">
		<div class="session-list" aria-label="Published conversations" aria-busy={listLoading}>
			<p class="status" role="status">{#if listLoading}{sessions.length ? 'Updating conversations… 正在更新会话…' : 'Loading conversations… 正在加载会话…'}{:else if listReady && sessions.length === 0}No conversations published yet. Publish explicitly from Fan yi Xia to add one.<br /><span lang="zh-Hans">暂无已发布会话，请在 Fan yi Xia 中主动发布。</span>{:else if listReady}{sessions.length} conversations loaded · 已加载会话{/if}</p>
			{#if listError}
				<div class="notice error" role="alert"><p>{listError}</p><button type="button" onclick={() => void loadSessions(retryRefresh)} disabled={listLoading}>Retry loading <span lang="zh-Hans">重试加载</span></button></div>
			{/if}
			{#if sessions.length > 0}
				<ul class="sessions">
					{#each sessions as item (item.id)}
						<li><button type="button" class="session-choice" class:selected={selectedId === item.id} aria-pressed={selectedId === item.id} onclick={() => void selectSession(item.id, selectedId === item.id)}>
							<span class="session-title">{item.title}</span>
							<span class="metadata">Recorded · 录制 <time datetime={item.recordedAt}>{dateLabel(item.recordedAt)}</time></span>
							<span class="metadata">Published · 发布 <time datetime={item.publishedAt}>{dateLabel(item.publishedAt)}</time></span>
							<span class="metadata">Whisper base → ML Kit · Offline engines</span>
						</button></li>
					{/each}
				</ul>
			{/if}
			{#if hasMore}<button class="load-more" type="button" onclick={() => void loadSessions(false)} disabled={listLoading}>Load more <span lang="zh-Hans">加载更多</span></button>{/if}
		</div>
		<div class="session-detail" aria-busy={detailLoading}>
			{#if !selectedId}
				<p class="detail-placeholder">Choose a conversation to read its saved translation and Qwen history.<br /><span lang="zh-Hans">选择会话，查看已保存的翻译与 Qwen 分析历史。</span></p>
			{:else}
				{#if detailLoading}<p class="status" role="status">Loading conversation and saved analyses… 正在加载会话与分析…</p>{/if}
				{#if detailError}<div class="notice error" role="alert"><p>{detailError}</p><button type="button" onclick={() => void selectSession(selectedId, true)} disabled={detailLoading}>Retry conversation <span lang="zh-Hans">重试</span></button></div>{/if}
				{#if session}
					<article aria-labelledby="saved-session-heading">
						<h3 id="saved-session-heading">{session.title}</h3>
						<p class="metadata">Recorded <time datetime={session.recordedAt}>{dateLabel(session.recordedAt)}</time><br />Published <time datetime={session.publishedAt}>{dateLabel(session.publishedAt)}</time><br />Transcription: Whisper base · Translation: ML Kit</p>
						<div class="saved-text chinese"><h4>Saved Chinese <span lang="zh-Hans">已保存中文</span></h4><div class="preserved" lang="zh-Hans">{session.chinese}</div></div>
						<div class="saved-text english"><h4>Saved English <span lang="zh-Hans">已保存英文</span></h4><div class="preserved" lang="en">{session.english}</div></div>
					</article>
					<section class="analysis-tools" aria-labelledby="analysis-heading" aria-busy={!!analysisLoading}>
						<div class="section-heading"><h3 id="analysis-heading">Qwen · Summary & reanalysis</h3><button type="button" onclick={() => void selectSession(selectedId, true)} disabled={detailLoading || !!analysisLoading}>Refresh history <span lang="zh-Hans">刷新历史</span></button></div>
						<p class="help">Uses only this conversation’s saved English. Every run saves a new public result without changing the original translation. AI output can be wrong.<br /><span lang="zh-Hans">仅分析此会话已保存的英文。每次运行都会保存新的公开结果，不覆盖原翻译。AI 输出可能有误。</span></p>
						<button class="primary" type="button" onclick={() => void runAnalysis('summary')} disabled={detailLoading || !!analysisLoading}>{analyses.some((item) => item.mode === 'summary') ? 'Run another summary · 再次总结' : 'Summarize saved English · 总结已保存英文'}</button>
						<label for="analysis-instruction">Custom reanalysis instruction <span lang="zh-Hans">自定义分析要求</span></label>
						<textarea id="analysis-instruction" bind:value={instruction} rows="3" maxlength="2000" placeholder="For example: explain the key decisions and unanswered questions." aria-describedby="instruction-help"></textarea>
						<p id="instruction-help" class="help">{instruction.length.toLocaleString()} / 2,000 characters · Your instruction and result will be public. <span lang="zh-Hans">要求和结果均会公开。</span></p>
						<button type="button" onclick={() => void runAnalysis('analysis')} disabled={detailLoading || !!analysisLoading || !instruction.trim() || instruction.length > 2000}>Run reanalysis <span lang="zh-Hans">运行分析</span></button>
						<p class="status" role="status">{#if analysisLoading}Qwen is {analysisLoading === 'summary' ? 'summarizing' : 'analyzing'} the saved English and saving the result… 正在分析并保存…{:else if analysisNotice}{analysisNotice}{/if}</p>
						{#if analysisError}<div class="notice error" role="alert"><p>{analysisError}</p><p>Existing translations and results are unchanged. If the connection failed after saving, refresh history before rerunning; each rerun creates a new public result.<br /><span lang="zh-Hans">已有翻译与结果不受影响。如保存后连接中断，请先刷新历史；每次重试均会新增公开结果。</span></p></div>{/if}
					</section>
					<section class="analysis-history" aria-labelledby="history-heading">
						<h3 id="history-heading">Saved Qwen history <span lang="zh-Hans">已保存分析历史</span></h3>
						<p class="help">Latest 50 results, newest first · 最近 50 条，最新在前</p>
						{#if analyses.length === 0}<p class="status">No saved analyses yet. Run a summary or provide an instruction above.<br /><span lang="zh-Hans">暂无分析，可在上方运行总结或输入分析要求。</span></p>{/if}
						<ol class="analyses">
							{#each analyses as result (result.id)}
								<li><article>
									<div class="section-heading"><h4>{result.mode === 'summary' ? 'Summary · 总结' : 'Reanalysis · 分析'}</h4><time class="metadata" datetime={result.createdAt}>{dateLabel(result.createdAt)}</time></div>
									{#if result.mode === 'analysis'}<p class="instruction preserved"><strong>Instruction · 要求:</strong> {result.instruction}</p>{/if}
									<div class="preserved result-text">{result.text}</div>
									<button type="button" onclick={() => void runAnalysis(result.mode, result.instruction)} disabled={detailLoading || !!analysisLoading}>Run again <span lang="zh-Hans">再次运行</span></button>
								</article></li>
							{/each}
						</ol>
					</section>
				{/if}
			{/if}
		</div>
	</div>
</section>

<style>
	.library { margin-top: 40px; padding-top: 28px; border-top: 1px solid var(--line); scroll-margin-top: 24px; }
	.section-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
	.eyebrow { margin: 0 0 8px; color: var(--accent-dark); font-size: 11px; font-weight: 600; letter-spacing: 0.07em; text-transform: uppercase; }
	h2, h3, h4 { margin: 0; line-height: 1.5; overflow-wrap: anywhere; }
	h2 { display: flex; flex-wrap: wrap; gap: 8px; font-size: 22px; font-weight: 500; letter-spacing: -0.03em; }
	h2 [lang='zh-Hans'] { font-size: 15px; }
	h3 { font-size: 17px; font-weight: 600; }
	h4 { font-size: 13px; font-weight: 600; }
	button { display: inline-flex; flex-wrap: wrap; align-items: baseline; justify-content: center; gap: 6px; min-height: 42px; padding: 10px 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--paper); color: var(--ink); font-size: 12px; font-weight: 600; cursor: pointer; }
	button:hover:not(:disabled) { border-color: var(--accent-dark); background: color-mix(in srgb, var(--paper), white 12%); }
	button:disabled { opacity: 0.5; cursor: not-allowed; }
	button:focus-visible, textarea:focus-visible { outline: 2px solid var(--accent-dark); outline-offset: 3px; }
	button.primary { border-color: var(--ink); background: var(--ink); color: var(--on-accent); }
	button.primary:hover:not(:disabled) { background: var(--accent-dark); }
	.public-warning { margin: 18px 0; padding: 14px 16px; border: 1px solid #b68d78; border-radius: 10px; background: #eee2cc; font-size: 12px; line-height: 1.8; }
	.library-layout { display: grid; grid-template-columns: minmax(210px, 0.75fr) minmax(0, 1.8fr); border: 1px solid var(--line); border-radius: 16px; overflow: hidden; background: var(--paper); }
	.session-list, .session-detail { min-width: 0; padding: clamp(16px, 2.5vw, 24px); }
	.session-list { background: #e8e5d6; }
	.session-detail { border-left: 1px solid var(--line); }
	.status, .help, .metadata, .detail-placeholder { color: var(--muted); font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
	.status { margin: 0 0 12px; }
	.status:empty { display: none; }
	.help { margin: 10px 0 14px; }
	.metadata { font-size: 11px; font-weight: 400; }
	.detail-placeholder { margin: 0; padding: 24px 0; }
	.sessions, .analyses { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; }
	.sessions { max-height: min(640px, 70vh); overflow-y: auto; padding: 3px; }
	.session-choice { display: flex; flex-direction: column; gap: 5px; width: 100%; align-items: stretch; text-align: left; overflow-wrap: anywhere; }
	.session-choice.selected { border-color: var(--accent-dark); background: #dde2ce; box-shadow: inset 3px 0 0 var(--accent-dark); }
	.session-title { font-size: 14px; }
	.load-more { width: 100%; margin-top: 14px; }
	.saved-text { margin-top: 18px; padding: 16px; border: 1px solid var(--line); border-radius: 10px; }
	.saved-text h4 { margin-bottom: 10px; }
	.chinese { background: #f2e8d9; }
	.english { background: #ede1ce; }
	.preserved { white-space: pre-wrap; overflow-wrap: anywhere; }
	.saved-text .preserved { font-size: 16px; line-height: 1.9; }
	.analysis-tools { margin-top: 28px; padding-top: 22px; border-top: 1px solid var(--line); }
	label { display: block; margin: 20px 0 8px; font-size: 12px; font-weight: 600; }
	textarea { display: block; box-sizing: border-box; width: 100%; min-width: 0; padding: 12px; border: 1px solid var(--line); border-radius: 10px; resize: vertical; background: #f2e8d9; color: var(--ink); font: inherit; font-size: 14px; line-height: 1.7; }
	.analysis-tools > .status { margin-top: 14px; }
	.notice { margin: 12px 0; padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
	.notice p { margin: 0 0 8px; }
	.notice p:last-child { margin-bottom: 0; }
	.error { border-color: #b68d78; background: #ecd9cc; color: #743e30; }
	.analysis-history { margin-top: 28px; }
	.analyses { gap: 16px; }
	.analyses > li { padding: 16px; border: 1px solid var(--line); border-radius: 10px; background: #f2e8d9; }
	.instruction { margin: 12px 0; color: var(--muted); font-size: 12px; line-height: 1.8; }
	.result-text { margin: 12px 0 16px; font-size: 14px; line-height: 1.9; }
	@media (max-width: 760px) {
		.library-layout { grid-template-columns: minmax(0, 1fr); }
		.session-detail { border-left: 0; border-top: 1px solid var(--line); }
		.sessions { max-height: 420px; overflow-y: auto; padding: 3px; }
		.saved-text { padding: 12px; }
		.section-heading > button { flex: 0 1 auto; }
	}
</style>
