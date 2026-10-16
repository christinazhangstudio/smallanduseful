<script lang="ts">
	import { onMount } from 'svelte';

	type Note = {
		id: string;
		text: string;
		color: number;
	};

	const papers = ['#f0d59a', '#e8c98a', '#f2d2ae', '#e4d0a2', '#dcc4a4'];

	let notes = $state<Note[]>([]);
	let loading = $state(true);
	let ready = $state(false);
	let loadError = $state(false);
	let saving = $state(false);
	let saveError = $state<'save' | 'delete' | null>(null);
	let saved = $state(false);
	let draftId = '';
	let draft = $state<string | null>(null);
	let editing = $state<string | null>(null);
	let editText = $state('');
	let invalid = $state(false);
	let copied = $state<string | null>(null);

	onMount(() => {
		void loadNotes();
	});

	function readNotes(value: unknown): Note[] {
		if (!Array.isArray(value)) throw new Error('Invalid notes response');
		const seen = new Set<string>();
		return value.map((item) => {
			if (
				!item || typeof item !== 'object' ||
				typeof item.id !== 'string' || !item.id || seen.has(item.id) ||
				typeof item.text !== 'string' || !item.text.trim() ||
				!Number.isInteger(item.color) || item.color < 0
			) {
				throw new Error('Invalid note');
			}
			seen.add(item.id);
			return { id: item.id, text: item.text, color: item.color };
		});
	}

	async function loadNotes() {
		if (saving) return;
		loading = true;
		loadError = false;
		try {
			const response = await fetch('/api/notes', { cache: 'no-store' });
			if (!response.ok) throw new Error('Could not load notes');
			notes = readNotes(await response.json());
			ready = true;
		} catch {
			loadError = true;
			ready = false;
		} finally {
			loading = false;
		}
	}

	async function putNote(note: Note): Promise<Note> {
		const response = await fetch(`/api/notes/${encodeURIComponent(note.id)}`, {
			method: 'PUT',
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ text: note.text, color: note.color })
		});
		if (!response.ok) throw new Error('Could not save note');
		const [confirmed] = readNotes([await response.json()]);
		if (confirmed.id !== note.id) throw new Error('Unexpected saved note');
		return confirmed;
	}

	function acceptNote(note: Note) {
		const index = notes.findIndex((current) => current.id === note.id);
		notes = index < 0
			? [note, ...notes]
			: notes.map((current) => current.id === note.id ? note : current);
	}

	function addNote() {
		if (!ready || saving || draft !== null || editing !== null) return;
		draftId = crypto.randomUUID();
		draft = '';
		invalid = false;
		saveError = null;
		saved = false;
	}

	async function saveDraft() {
		if (!ready || saving || draft === null) return;
		const text = draft.trim();
		if (!text) {
			invalid = true;
			return;
		}
		saving = true;
		saveError = null;
		saved = false;
		try {
			const confirmed = await putNote({ id: draftId, text, color: notes.length % papers.length });
			acceptNote(confirmed);
			draft = null;
			invalid = false;
			saved = true;
		} catch {
			saveError = 'save';
		} finally {
			saving = false;
		}
	}

	function startEdit(note: Note) {
		if (!ready || saving || draft !== null || editing !== null) return;
		editing = note.id;
		editText = note.text;
		invalid = false;
		saveError = null;
		saved = false;
	}

	async function saveEdit(id: string) {
		if (!ready || saving || editing !== id) return;
		const text = editText.trim();
		if (!text) {
			invalid = true;
			return;
		}
		const original = notes.find((note) => note.id === id);
		if (!original) return;
		saving = true;
		saveError = null;
		saved = false;
		try {
			acceptNote(await putNote({ ...original, text }));
			editing = null;
			invalid = false;
			saved = true;
		} catch {
			saveError = 'save';
		} finally {
			saving = false;
		}
	}

	async function deleteNote(id: string) {
		if (!ready || saving || draft !== null || editing !== null) return;
		saving = true;
		saveError = null;
		saved = false;
		try {
			const response = await fetch(`/api/notes/${encodeURIComponent(id)}`, { method: 'DELETE' });
			if (!response.ok) throw new Error('Could not delete note');
			notes = notes.filter((note) => note.id !== id);
			saved = true;
		} catch {
			saveError = 'delete';
		} finally {
			saving = false;
		}
	}

	function cancelWriting() {
		if (saving) return;
		draft = null;
		editing = null;
		invalid = false;
		saveError = null;
	}

	function copyNote(id: string, text: string) {
		void navigator.clipboard.writeText(text).then(() => {
			copied = id;
			setTimeout(() => {
				if (copied === id) copied = null;
			}, 1200);
		});
	}

	function tilt(id: string) {
		const hash = [...id].reduce((total, char) => total + char.charCodeAt(0), 0);
		return ((hash % 5) - 2) * 0.7;
	}
</script>

<svelte:head>
	<title>Notes · 便签 — Small Useful Page 小万事页</title>
	<meta name="description" content="A public shared notes board, saved across browsers. Anyone can read, edit, or delete notes. 跨浏览器保存的公开共享便签板，任何人都可以查看、编辑或删除。" />
</svelte:head>

<div class="site-shell">
	<main>
		<section class="intro">
			<p class="eyebrow"><span>04 / notes</span><span lang="zh-Hans">便签</span></p>
			<h1><span>Notes</span><span lang="zh-Hans">便签</span></h1>
		</section>

		<div class="toolbar">
			<button type="button" onclick={addNote} disabled={!ready || saving || draft !== null || editing !== null}>
				<span>New note</span><span lang="zh-Hans">新便签</span>
			</button>
			{#if ready}
				<p><span>{notes.length} shared notes</span><span lang="zh-Hans">{notes.length} 张共享便签</span></p>
			{/if}
		</div>

		<div class="sync-status" role="status" aria-live="polite">
			{#if loading}
				<p><span>Loading shared notes…</span><span lang="zh-Hans">正在加载共享便签…</span></p>
			{:else if loadError}
				<p class="error"><span>Could not load shared notes. Check your connection and retry.</span><span lang="zh-Hans">无法加载共享便签，请检查网络连接后重试。</span></p>
				<button type="button" onclick={loadNotes}><span>Retry loading</span><span lang="zh-Hans">重试加载</span></button>
			{:else if saving}
				<p><span>Waiting for server confirmation…</span><span lang="zh-Hans">正在等待服务器确认…</span></p>
			{:else if saveError === 'save'}
				<p class="error"><span>Save not confirmed. Your text is still in the editor. Press Save to retry.</span><span lang="zh-Hans">保存未获确认。文字仍保留在编辑框中，请点击“保存”重试。</span></p>
			{:else if saveError === 'delete'}
				<p class="error"><span>Delete not confirmed. The note is still shown; use its delete button to retry.</span><span lang="zh-Hans">删除未获确认。便签仍保留在页面上，请点击删除按钮重试。</span></p>
			{:else if draft !== null || editing !== null}
				<p><span>Unsaved text — press Save to share it.</span><span lang="zh-Hans">文字尚未保存，点击“保存”后才会共享。</span></p>
			{:else if saved}
				<p><span>Change confirmed by the server.</span><span lang="zh-Hans">更改已获服务器确认。</span></p>
			{:else if ready}
				<p><span>Shared board loaded from the server.</span><span lang="zh-Hans">已从服务器加载共享便签板。</span></p>
			{/if}
		</div>

		{#if notes.length > 0 || draft !== null}
			<div class="board">
				{#if draft !== null}
					<article class="note writing">
						<textarea bind:value={draft} disabled={saving} placeholder="Write a note · 写便签" aria-label="New note · 新便签"></textarea>
						{#if invalid}<p class="invalid"><span>A note needs text.</span><span lang="zh-Hans">便签不能是空的。</span></p>{/if}
						<div class="actions">
							<button type="button" disabled={!ready || saving} onclick={saveDraft}><span>Save</span><span lang="zh-Hans">保存</span></button>
							<button type="button" disabled={saving} class="quiet" onclick={cancelWriting}><span>Cancel</span><span lang="zh-Hans">取消</span></button>
						</div>
					</article>
				{/if}
				{#each notes as note (note.id)}
					<article class="note" style:background={papers[note.color % papers.length]} style:rotate="{tilt(note.id)}deg">
						<button type="button" class="close" aria-label="Delete note · 删除便签" disabled={!ready || saving || draft !== null || editing !== null} onclick={() => deleteNote(note.id)}>×</button>
						{#if editing === note.id}
							<textarea bind:value={editText} disabled={saving} aria-label="Edit note · 编辑便签"></textarea>
							{#if invalid}<p class="invalid"><span>A note needs text.</span><span lang="zh-Hans">便签不能是空的。</span></p>{/if}
							<div class="actions">
								<button type="button" disabled={!ready || saving} onclick={() => saveEdit(note.id)}><span>Save</span><span lang="zh-Hans">保存</span></button>
								<button type="button" disabled={saving} class="quiet" onclick={cancelWriting}><span>Cancel</span><span lang="zh-Hans">取消</span></button>
							</div>
						{:else}
							<p>{note.text}</p>
							<div class="stack">
								<button type="button" class="edit" disabled={!ready || saving || draft !== null || editing !== null} onclick={() => startEdit(note)}><span>Edit</span><span lang="zh-Hans">编辑</span></button>
								<button type="button" class="edit" onclick={() => copyNote(note.id, note.text)}>
									{#if copied === note.id}
										<span>Copied</span><span lang="zh-Hans">已复制</span>
									{:else}
										<span>Copy</span><span lang="zh-Hans">复制</span>
									{/if}
								</button>
							</div>
						{/if}
					</article>
				{/each}
			</div>
		{:else if ready}
			<p class="empty"><span>No shared notes yet.</span><span lang="zh-Hans">还没有共享便签。</span></p>
		{/if}
	</main>
</div>

<style>
	.site-shell {
		width: min(100%, 1120px);
		min-height: 100%;
		margin: 0 auto;
		padding-inline: clamp(16px, 3vw, 40px);
	}

	main {
		width: 100%;
		min-width: 0;
		padding: clamp(28px, 5vw, 56px) 0 clamp(40px, 7vw, 72px);
	}

	.intro {
		margin-bottom: 28px;
	}

	.eyebrow,
	.toolbar button,
	.toolbar p,
	.empty {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 6px;
	}

	.eyebrow {
		margin: 0 0 9px;
		color: var(--accent-dark);
		font-size: 12px;
		font-weight: 600;
		letter-spacing: 0.08em;
		text-transform: uppercase;
	}

	h1 {
		margin: 0;
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 10px;
		font-size: clamp(34px, 4.6vw + 1rem, 72px);
		font-weight: 500;
		letter-spacing: -0.065em;
		line-height: 0.95;
	}

	h1 [lang='zh-Hans'] {
		font-size: 0.28em;
		font-weight: 500;
	}

	.sync-status {
		margin-bottom: 24px;
		color: var(--muted);
		font-size: 13px;
	}

	.sync-status p {
		display: flex;
		flex-wrap: wrap;
		gap: 6px 12px;
	}

	.sync-status p {
		margin: 0 0 10px;
	}

	.sync-status .error {
		color: #8a3d32;
	}

	.sync-status button {
		border: 1px solid var(--line);
		border-radius: 6px;
		padding: 8px 12px;
		background: transparent;
		color: inherit;
		cursor: pointer;
	}

	button:disabled {
		opacity: 0.45;
		cursor: not-allowed;
	}

	.toolbar {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 12px 16px;
		margin-bottom: 22px;
	}

	.toolbar button {
		border: 0;
		border-radius: 10px;
		padding: 10px 14px;
		background: var(--accent);
		color: var(--on-accent);
		cursor: pointer;
		font-weight: 600;
	}

	.toolbar button [lang='zh-Hans'] {
		color: #fff;
		font-size: 11px;
	}

	.toolbar p,
	.empty {
		margin: 0;
		color: var(--muted);
		font-size: 12px;
	}

	.board {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 220px), 1fr));
		gap: 16px;
		align-items: start;
	}

	.note {
		display: grid;
		grid-template-columns: 1fr auto;
		min-height: 180px;
		padding: 12px 12px 16px;
		border-radius: 2px 14px 14px 14px;
		box-shadow: 0 10px 18px color-mix(in srgb, var(--ink) 12%, transparent);
		color: #3f342c;
	}

	.note button.close {
		width: 28px;
		height: 28px;
		justify-self: end;
		border: 0;
		background: transparent;
		color: color-mix(in srgb, #3f342c 55%, transparent);
		cursor: pointer;
		font-size: 18px;
		line-height: 1;
	}

	.note p {
		grid-column: 1 / -1;
		margin: 0;
		white-space: pre-wrap;
	}

	.actions,
	.edit {
		grid-column: 1 / -1;
		display: flex;
		justify-content: flex-end;
		gap: 10px;
		width: fit-content;
		margin-left: auto;
	}

	.stack {
		grid-column: 1 / -1;
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		gap: 4px;
	}

	.actions button,
	.edit {
		border: 0;
		padding: 0;
		background: transparent;
		color: color-mix(in srgb, #3f342c 42%, transparent);
		cursor: pointer;
		font-size: 15px;
		font-weight: 500;
	}
	.actions [lang='zh-Hans'],
	.edit [lang='zh-Hans'] {
		font-size: 14px;
	}

	.actions button:hover,
	.edit:hover {
		color: #3f342c;
	}

	.actions button.quiet {
		background: transparent;
	}

	.invalid {
		grid-column: 1 / -1;
		margin: 0;
		color: #8a3d32;
		font-size: 12px;
	}
	.invalid [lang='zh-Hans'] {
		font-size: 12px;
	}

	textarea {
		grid-column: 1 / -1;
		width: 100%;
		min-height: 96px;
		margin: 0;
		padding: 0;
		border: 0;
		resize: vertical;
		background: transparent;
		color: inherit;
		line-height: 1.45;
	}

	textarea:focus {
		outline: none;
	}
</style>
