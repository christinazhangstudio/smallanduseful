<script lang="ts">
	import {
		countBookmarks,
		isFolder,
		parseBookmarks,
		removeBookmark,
		serializeBookmarks,
		type Bookmark,
		type BookmarkFolder
	} from '$lib/bookmarks';

	const storageKey = 'smallanduseful.bookmarks';

	let root = $state<BookmarkFolder | null>(null);
	let query = $state('');
	let error = $state('');
	let input = $state<HTMLInputElement>();
	let source = $state('');

	const visible = $derived(root ? filterFolder(root, query.trim().toLowerCase()) : null);
	const marks = ['#9a624c', '#6d7a62', '#7a6858', '#8a5a62', '#5f6e78', '#8a7348'];

	function hostOf(href: string) {
		try {
			return new URL(href).hostname.replace(/^www\./, '');
		} catch {
			return href;
		}
	}

	function markOf(href: string) {
		const host = hostOf(href);
		let hash = 0;
		for (const char of host) hash = (hash + char.charCodeAt(0)) % marks.length;
		return marks[hash];
	}

	function initialOf(href: string, title: string) {
		const host = hostOf(href);
		return (host[0] || title[0] || '?').toUpperCase();
	}

	const total = $derived(root ? countBookmarks(root) : 0);

	$effect(() => {
		const saved = localStorage.getItem(storageKey);
		if (!saved || root) return;
		try {
			root = parseBookmarks(saved);
			source = saved;
		} catch {
			localStorage.removeItem(storageKey);
		}
	});

	function importFile(file: File) {
		error = '';
		file
			.text()
			.then((html) => {
				const parsed = parseBookmarks(html);
				const stored = serializeBookmarks(parsed);
				localStorage.setItem(storageKey, stored);
				source = stored;
				root = parseBookmarks(stored);
			})
			.catch((cause: unknown) => {
				error = cause instanceof Error ? cause.message : 'Could not read that file.';
			});
	}

	function clearBookmarks() {
		localStorage.removeItem(storageKey);
		root = null;
		error = '';
	}
	function save(folder: BookmarkFolder) {
		const html = serializeBookmarks(folder);
		localStorage.setItem(storageKey, html);
		source = html;
		root = parseBookmarks(html);
	}

	function deleteBookmark(id: string) {
		if (!root) return;
		const next = removeBookmark(root, id);
		if (countBookmarks(next) === 0) {
			clearBookmarks();
			return;
		}
		save(next);
	}

	function downloadExport() {
		if (!root) return;
		const html = serializeBookmarks(root);
		source = html;
		localStorage.setItem(storageKey, html);
		const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
		const url = URL.createObjectURL(blob);
		const link = document.createElement('a');
		link.href = url;
		link.download = 'bookmarks.html';
		link.click();
		URL.revokeObjectURL(url);
	}

	function filterFolder(folder: BookmarkFolder, term: string): BookmarkFolder | null {
		if (!term) return folder;
		const items: Array<Bookmark | BookmarkFolder> = [];
		for (const item of folder.items) {
			if (isFolder(item)) {
				const nested = filterFolder(item, term);
				if (nested) items.push(nested);
			} else if (
				item.title.toLowerCase().includes(term) ||
				item.href.toLowerCase().includes(term)
			) {
				items.push(item);
			}
		}
		if (items.length === 0 && !folder.title.toLowerCase().includes(term)) return null;
		return { ...folder, items };
	}

</script>

<svelte:head>
	<title>Bookmarks · 书签 — Small Useful Page 小万事页</title>
	<meta
		name="description"
		content="Import a browser bookmark export and keep it on this device. 导入浏览器书签，只保存在本机。"
	/>
</svelte:head>

<div class="site-shell">
	<main>
		<section class="intro">
			<p class="eyebrow"><span>02 / bookmarks</span><span lang="zh-Hans">书签</span></p>
			<h1><span>Bookmarks</span><span lang="zh-Hans">书签</span></h1>
		</section>

		{#if !root}
			<input
				bind:this={input}
				class="file-input"
				type="file"
				accept=".html,text/html"
				onchange={(event) => {
					const file = event.currentTarget.files?.[0];
					if (file) importFile(file);
				}}
			/>
			<button class="dropzone" type="button" onclick={() => input?.click()}>
				<span class="plus" aria-hidden="true">+</span>
				<span class="drop-copy"><span>Choose a bookmark export</span><span lang="zh-Hans">选择书签导出文件</span></span>
				<small>HTML · NETSCAPE</small>
			</button>
			<ol class="steps">
				<li><span>Chrome, Edge, or Brave: Bookmarks manager → ⋮ → Export bookmarks.</span><span lang="zh-Hans">Chrome、Edge 或 Brave：书签管理器 → ⋮ → 导出书签。</span></li>
				<li><span>Firefox or Safari: export bookmarks as HTML.</span><span lang="zh-Hans">Firefox 或 Safari：将书签导出为 HTML。</span></li>
				<li><span>The file stays in this browser. Nothing is uploaded.</span><span lang="zh-Hans">文件只留在这个浏览器里，不会上传。</span></li>
			</ol>
			{#if error}<p class="error">{error}</p>{/if}
		{:else}
			<div class="toolbar">
				<label>
					<span class="visually-hidden">Search bookmarks</span>
					<input bind:value={query} type="search" placeholder="Search" />
				</label>
				<p><span>{total} bookmarks</span><span lang="zh-Hans">{total} 个书签</span></p>
				<button type="button" onclick={downloadExport}><span>Download HTML</span><span lang="zh-Hans">下载 HTML</span></button>
				<button type="button" onclick={clearBookmarks}><span>Clear</span><span lang="zh-Hans">清除</span></button>
			</div>
			{#if error}<p class="error">{error}</p>{/if}
			{#if visible && visible.items.length > 0}
			<div class="board">
				{#each visible.items as item (item.id)}
					{@render node(item)}
				{/each}
			</div>
			{:else}
				<p class="empty"><span>No matches.</span><span lang="zh-Hans">没有匹配。</span></p>
			{/if}
		{/if}
	</main>
	<footer><span>The export never leaves this browser.</span><span lang="zh-Hans">导出文件不会离开这个浏览器。</span></footer>
</div>

{#snippet node(item: Bookmark | BookmarkFolder, depth = 0)}
	{#if isFolder(item)}
		<details class="folder" class:nested={depth > 0} open={query.trim().length > 0}>
			<summary class="card">
				<span class="folder-icon" aria-hidden="true">
					<svg viewBox="0 0 24 24">
						<path class="folder-back" d="M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2h7.5A2.5 2.5 0 0 1 21 9.5V11H3V7.5Z" />
						<path class="folder-front" d="M3 10.5h18l-1.6 7.2A2.5 2.5 0 0 1 16.95 20H6.4a2.5 2.5 0 0 1-2.45-2.3L3 10.5Z" />
					</svg>
				</span>
				<span class="card-copy">
					<span class="folder-name">{item.title}</span>
					<small>{countBookmarks(item)}</small>
				</span>
				<button type="button" aria-label={`Delete ${item.title}`} onclick={() => deleteBookmark(item.id)}>×</button>
			</summary>
			<div class="cards">
				{#each item.items as child (child.id)}
					{@render node(child, depth + 1)}
				{/each}
			</div>
		</details>
	{:else}
		<article class="card">
			<a href={item.href} target="_blank" rel="external">
				<span class="mark" style:background={markOf(item.href)} aria-hidden="true">
					<img
						src={`https://www.google.com/s2/favicons?domain=${encodeURIComponent(hostOf(item.href))}&sz=64`}
						alt=""
						width="22"
						height="22"
						loading="lazy"
						onerror={(event) => {
							const image = event.currentTarget;
							if (image instanceof HTMLImageElement) image.hidden = true;
						}}
					/>
					<span>{initialOf(item.href, item.title)}</span>
				</span>
				<span class="card-copy">
					<span>{item.title}</span>
					<small>{hostOf(item.href)}</small>
				</span>
			</a>
			<button type="button" aria-label={`Delete ${item.title}`} onclick={() => deleteBookmark(item.id)}>
				×
			</button>
		</article>
	{/if}
{/snippet}

<style>
	.site-shell {
		width: min(100%, 1280px);
		min-height: 100%;
		margin: 0 auto;
		padding-inline: clamp(16px, 3vw, 40px);
		display: grid;
		grid-template-rows: auto 1fr auto;
	}

	main {
		width: 100%;
		min-width: 0;
		padding: clamp(28px, 5vw, 56px) 0 clamp(40px, 7vw, 72px);
	}

	.intro {
		margin-bottom: 30px;
	}

	.eyebrow,
	.drop-copy,
	.toolbar p,
	.toolbar button,
	footer,
	.empty,
	.steps li {
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

	footer {
		padding: 24px 0;
		border-top: 1px solid var(--line);
		font-size: 12px;
	}

	.file-input,
	.visually-hidden {
		position: fixed;
		width: 1px;
		height: 1px;
		opacity: 0;
		pointer-events: none;
	}

	.dropzone,
	.toolbar,
	.steps,
	.card,
	.folder {
		border: 1px solid var(--line);
		border-radius: 16px;
		background: var(--paper);
	}

	.dropzone {
		width: 100%;
		min-height: clamp(160px, 28vh, 220px);
		padding: clamp(20px, 4vw, 32px) clamp(12px, 3vw, 24px);
		border-style: dashed;
		color: var(--ink);
		cursor: pointer;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 14px;
	}

	.plus {
		width: 42px;
		height: 42px;
		border-radius: 50%;
		background: var(--ink);
		color: var(--on-accent);
		display: grid;
		place-items: center;
		font-size: 25px;
		font-weight: 300;
	}

	.drop-copy {
		justify-content: center;
		max-width: 100%;
		padding: 0 16px;
		text-align: center;
	}

	.steps li {
		align-items: flex-start;
	}

	.dropzone small,
	.steps,
	.empty,
	footer {
		color: var(--muted);
	}

	.dropzone small {
		font-size: 11px;
		letter-spacing: 0.08em;
	}

	.steps {
		margin: 16px 0 0;
		padding: 8px clamp(12px, 2vw, 20px) 8px clamp(28px, 4vw, 40px);
	}

	.steps li {
		padding: 12px 0;
		border-bottom: 1px solid var(--line);
	}

	.steps li:last-child {
		border-bottom: 0;
	}

	.toolbar {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 10px 14px;
		margin-bottom: 16px;
		padding: 14px;
	}

	.toolbar label {
		flex: 1 1 180px;
		min-width: 0;
	}

	.toolbar input {
		width: 100%;
		min-width: 0;
		padding: 10px 12px;
		border: 1px solid var(--line);
		border-radius: 10px;
		background: transparent;
		color: var(--ink);
	}

	.toolbar p {
		margin: 0;
		color: var(--muted);
		font-size: 12px;
	}

	.toolbar button {
		border: 0;
		background: transparent;
		color: var(--accent-dark);
		cursor: pointer;
		font-size: 13px;
		font-weight: 600;
	}

	.toolbar button [lang='zh-Hans'],
	footer [lang='zh-Hans'] {
		font-size: 11px;
	}

	.board,
	.cards {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 220px), 1fr));
		gap: 12px;
	}

	.board > .folder,
	.cards > .folder {
		grid-column: 1 / -1;
	}

	.folder {
		display: grid;
		min-width: 0;
		border: 0;
		background: transparent;
	}

	.folder > .cards {
		min-width: 0;
		margin-top: 12px;
	}


	.folder.nested > .card {
		background: color-mix(in srgb, var(--paper) 78%, var(--line));
	}

	summary.card {
		cursor: pointer;
		list-style: none;
	}

	summary::-webkit-details-marker {
		display: none;
	}

	.card {
		display: flex;
		align-items: center;
		gap: 12px;
		min-width: 0;
		min-height: 76px;
		padding: 12px;
		background: color-mix(in srgb, var(--paper) 82%, #fff4e4);
		box-shadow: 0 1px 0 color-mix(in srgb, var(--ink) 4%, transparent);
	}

	.card:hover {
		border-color: var(--accent);
		transform: translateY(-2px);
	}

	.card > a {
		display: flex;
		flex: 1;
		align-items: center;
		gap: 12px;
		min-width: 0;
		color: inherit;
		text-decoration: none;
	}

	.folder-icon,
	.mark {
		display: grid;
		flex: 0 0 auto;
		width: 42px;
		height: 42px;
		place-items: center;
	}

	.folder-icon svg {
		width: 34px;
		height: 34px;
	}

	.folder-back {
		fill: #c4a574;
	}

	.folder-front {
		fill: #e4c98a;
		stroke: #9a7a45;
		stroke-width: 0.6;
	}

	.folder[open] > .card .folder-front {
		fill: #f0d7a2;
	}

	.mark {
		position: relative;
		border-radius: 12px;
		color: var(--on-accent);
		font-size: 15px;
		font-weight: 600;
	}

	.mark img {
		position: absolute;
		width: 22px;
		height: 22px;
		border-radius: 5px;
		background: #fff;
	}

	.card-copy {
		display: grid;
		flex: 1;
		gap: 3px;
		min-width: 0;
	}

	.card-copy span,
	.card-copy small {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.card-copy span {
		font-weight: 600;
		letter-spacing: -0.02em;
	}

	.card-copy small {
		color: var(--muted);
		font-size: 12px;
	}

	.card > button {
		width: 28px;
		flex: 0 0 auto;
		border: 0;
		background: transparent;
		color: var(--muted);
		cursor: pointer;
		font: inherit;
		font-size: 18px;
		line-height: 1;
	}

	.card > button:hover {
		color: var(--accent-dark);
	}

	.folder:not([open]) > .cards {
		display: none;
	}



	.mark img {
		position: absolute;
		width: 22px;
		height: 22px;
		border-radius: 5px;
		background: #fff;
	}

	.mark img:not([src]),
	.mark img[src=''] {
		display: none;
	}


	.error {
		color: var(--accent-dark);
		font-size: 13px;
	}

	.empty {
		margin: 0;
	}

	footer {
		align-items: baseline;
	}
	@media (max-width: 720px) {
		.toolbar {
			align-items: stretch;
		}

		.toolbar label,
		.toolbar p,
		.toolbar button {
			flex: 1 1 100%;
		}
	}
</style>
