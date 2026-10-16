<script lang="ts">
	import { onDestroy } from 'svelte';
	import { compressImage } from '$lib/compression/compress';

	type ImageItem = {
		id: string;
		file: File;
		name: string;
		status: 'ready' | 'compressing' | 'done' | 'error';
		output?: Blob;
		outputUrl?: string;
		outputType?: 'image/jpeg' | 'image/webp';
		unchanged?: boolean;
		error?: string;
	};

	let items = $state<ImageItem[]>([]);
	let quality = $state(0.8);
	let input: HTMLInputElement;
	let dragging = $state(false);
	let running = $derived(items.some((item) => item.status === 'compressing'));
	let readyCount = $derived(items.filter((item) => item.status === 'ready' || item.status === 'error').length);

	const acceptedTypes = new Set(['image/jpeg', 'image/png', 'image/webp']);

	function addFiles(files: FileList | File[]) {
		const next = Array.from(files)
			.filter((file) => acceptedTypes.has(file.type))
			.map((file) => ({
				id: crypto.randomUUID(),
				file,
				name: file.name.replace(/\.[^.]+$/, ''),
				status: 'ready' as const
			}));
		items.push(...next);
		if (input) input.value = '';
	}

	function onDrop(event: DragEvent) {
		event.preventDefault();
		dragging = false;
		if (event.dataTransfer?.files) addFiles(event.dataTransfer.files);
	}

	function remove(item: ImageItem) {
		if (item.outputUrl) URL.revokeObjectURL(item.outputUrl);
		items = items.filter((candidate) => candidate.id !== item.id);
	}

	async function compressAll() {
		for (const item of items) {
			if (item.status !== 'ready' && item.status !== 'error') continue;
			item.status = 'compressing';
			item.error = undefined;
			if (item.outputUrl) URL.revokeObjectURL(item.outputUrl);

			try {
				const result = await compressImage(item.file, quality);
				item.outputType = result.type;
				item.unchanged = result.blob.size >= item.file.size;
				if (!item.unchanged) {
					item.output = result.blob;
					item.outputUrl = URL.createObjectURL(result.blob);
				}
				item.status = 'done';
			} catch (error) {
				item.status = 'error';
				item.error = error instanceof Error ? error.message : 'Compression failed';
			}
		}
	}

	function resetResults() {
		for (const item of items) {
			if (item.status !== 'done') continue;
			if (item.outputUrl) URL.revokeObjectURL(item.outputUrl);
			item.output = undefined;
			item.outputUrl = undefined;
			item.outputType = undefined;
			item.unchanged = undefined;
			item.status = 'ready';
		}
	}

	function formatBytes(bytes: number) {
		if (bytes < 1000) return `${bytes} B`;
		if (bytes < 1_000_000) return `${(bytes / 1000).toFixed(1)} KB`;
		return `${(bytes / 1_000_000).toFixed(1)} MB`;
	}

	function saving(item: ImageItem) {
		if (!item.output) return '';
		const difference = 1 - item.output.size / item.file.size;
		return `−${Math.round(difference * 100)}%`;
	}

	function outputName(item: ImageItem) {
		const stem = item.name.trim() || item.file.name.replace(/\.[^.]+$/, '');
		return `${stem}.${item.outputType === 'image/jpeg' ? 'jpg' : 'webp'}`;
	}

	onDestroy(() => {
		for (const item of items) if (item.outputUrl) URL.revokeObjectURL(item.outputUrl);
	});
</script>

<svelte:head>
	<title>Compress images · 压缩图片 — Small Useful Page 小万事页</title>
	<meta
		name="description"
		content="Compress JPEG, PNG, and WebP images privately in your browser. 在浏览器中私密压缩图片。"
	/>
</svelte:head>

<div class="site-shell">

	<main>
		<section class="intro">
			<p class="eyebrow"><span>01 / images</span><span lang="zh-Hans">图片</span></p>
			<h1><span>Compress</span><span lang="zh-Hans">压缩图片</span></h1>
		</section>

		<input
			bind:this={input}
			class="file-input"
			type="file"
			accept="image/jpeg,image/png,image/webp"
			multiple
			onchange={(event) => addFiles(event.currentTarget.files ?? [])}
		/>

		<button
			class:dragging
			class="dropzone"
			type="button"
			onclick={() => input.click()}
			ondragenter={(event) => {
				event.preventDefault();
				dragging = true;
			}}
			ondragover={(event) => event.preventDefault()}
			ondragleave={() => (dragging = false)}
			ondrop={onDrop}
		>
			<span class="plus" aria-hidden="true">+</span>
			<span class="drop-copy"><span>Choose or drop images</span><span lang="zh-Hans">选择或拖入图片</span></span>
			<small>JPEG · PNG · WebP</small>
		</button>

		{#if items.length > 0}
			<section class="workspace" aria-label="Selected images">
				<div class="controls">
					<label for="quality">
						<span><span>Quality</span><span lang="zh-Hans">质量</span></span>
						<strong>{Math.round(quality * 100)}%</strong>
					</label>
					<input id="quality" type="range" min="0.4" max="0.95" step="0.05" bind:value={quality} oninput={resetResults} disabled={running} />
				</div>

				<ul class="file-list">
					{#each items as item (item.id)}
						<li>
							<div class="file-copy">
								<label class="rename">
									<span><span>Rename</span><span lang="zh-Hans">重命名</span></span>
									<input
										value={item.name}
										aria-label={`Rename ${item.file.name}`}
										oninput={(event) => (item.name = event.currentTarget.value)}
									/>
								</label>
								<span class="sizes">{formatBytes(item.file.size)}{item.output ? ` → ${formatBytes(item.output.size)}` : ''}</span>
								{#if item.status !== 'done' && item.file.size < 2_000_000}
									<span class="under"><span>Already less than 2MB!</span><span lang="zh-Hans">已经小于 2MB！</span></span>
								{:else if (item.output?.size ?? item.file.size) < 2_000_000}
									<span class="under"><span>Less than 2MB!</span><span lang="zh-Hans">小于 2MB！</span></span>
								{/if}
							</div>
							<div class="file-actions">
								{#if item.status === 'compressing'}
									<span class="status"><span>working</span><span lang="zh-Hans">处理中</span></span>
								{:else if item.status === 'done' && item.outputUrl}
									<span class="saving">{saving(item)}</span>
									<a class="download" href={item.outputUrl} download={outputName(item)}>
										<svg viewBox="0 0 16 16" aria-hidden="true">
											<path
												d="M8 2.5v7M5.2 7.2 8 10l2.8-2.8M3.5 12.5h9"
												fill="none"
												stroke="currentColor"
												stroke-width="1.6"
												stroke-linecap="round"
												stroke-linejoin="round"
											/>
										</svg>
										<span>Download</span><span lang="zh-Hans">下载</span>
									</a>
								{:else if item.status === 'done' && item.unchanged}
									<span class="optimized"><span>Already optimized</span><span lang="zh-Hans">已优化</span></span>
								{:else if item.status === 'error'}
									<span class="error" title={item.error}>{item.error}</span>
								{/if}
								<button class="remove" type="button" onclick={() => remove(item)} aria-label={`Remove ${item.file.name}`}>×</button>
							</div>
						</li>
					{/each}
				</ul>

				{#if readyCount > 0}
					<button class="compress" type="button" onclick={compressAll} disabled={running}>
						<span>{running ? 'Compressing…' : 'Compress'}</span>
						<span lang="zh-Hans">{running ? '压缩中…' : '开始压缩'}</span>
					</button>
				{/if}
			</section>
		{/if}
	</main>

	<footer><span>Files never leave this device.</span><span lang="zh-Hans">文件不会离开本机。</span></footer>
</div>

<style>
	.site-shell {
		width: min(100%, 960px);
		min-height: 100%;
		margin: 0 auto;
		padding-inline: clamp(16px, 3vw, 40px);
		display: grid;
		grid-template-rows: auto 1fr auto;
	}

	footer {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 6px;
		padding: 24px 0;
		border-top: 1px solid var(--line);
		color: var(--muted);
		font-size: 12px;
	}

	footer [lang='zh-Hans'] {
		font-size: 11px;
	}

	main {
		align-self: center;
		width: 100%;
		min-width: 0;
		padding: clamp(28px, 5vw, 56px) 0 clamp(40px, 7vw, 72px);
	}

	.intro {
		margin-bottom: 30px;
	}

	.eyebrow,
	.drop-copy,
	.controls label > span,
	.status,
	.optimized,
	.download,
	.compress {
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

	.file-input {
		position: fixed;
		width: 1px;
		height: 1px;
		opacity: 0;
		pointer-events: none;
	}

	.dropzone {
		width: 100%;
		min-height: clamp(160px, 28vh, 235px);
		padding: clamp(20px, 4vw, 32px) clamp(12px, 3vw, 24px);
		border: 1.5px dashed var(--line);
		border-radius: 18px;
		background: var(--paper);
		color: var(--ink);
		cursor: pointer;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 14px;
		transition: 160ms ease;
	}

	.dropzone:hover,
	.dropzone.dragging {
		border-color: var(--accent);
		background: var(--paper);
		transform: translateY(-2px);
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
		font-weight: 500;
		text-align: center;
	}

	.dropzone small {
		color: var(--muted);
		font-size: 11px;
		letter-spacing: 0.08em;
	}

	.workspace {
		margin-top: 16px;
		border: 1px solid var(--line);
		border-radius: 18px;
		background: var(--paper);
		overflow: hidden;
	}

	.controls {
		padding: 20px;
		border-bottom: 1px solid var(--line);
	}

	.controls label {
		display: flex;
		justify-content: space-between;
		margin-bottom: 14px;
		font-size: 13px;
	}

	.controls label > span {
		min-width: 0;
	}

	.controls input {
		width: 100%;
		accent-color: var(--accent);
	}

	.file-list {
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.file-list li {
		min-height: 74px;
		padding: 14px clamp(12px, 2vw, 20px);
		border-bottom: 1px solid var(--line);
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		align-items: center;
		gap: 8px 12px;
	}

	.file-actions {
		display: flex;
		align-items: center;
		justify-content: flex-end;
		gap: 10px;
	}

	.file-copy {
		min-width: 0;
		display: grid;
		gap: 5px;
	}

	.rename {
		display: grid;
		gap: 2px;
		min-width: 0;
	}

	.rename > span {
		display: flex;
		gap: 6px;
		color: var(--muted);
		font-size: 11px;
		font-weight: 600;
	}

	.file-copy input {
		width: 100%;
		min-width: 0;
		padding: 4px 6px;
		border: 1px solid transparent;
		border-radius: 6px;
		background: transparent;
		color: var(--ink);
		font-size: 14px;
		font-weight: 500;
	}

	.rename:hover input,
	.file-copy input:focus {
		border-color: var(--line);
		background: color-mix(in srgb, var(--paper) 70%, #fff);
	}

	.file-copy span,
	.status,
	.optimized,
	.error {
		color: var(--muted);
		font-size: 12px;
	}

	.sizes {
		color: var(--ink);
		font-size: 16px;
		font-weight: 600;
	}

	.file-copy .under,
	.file-copy .under span {
		display: flex;
		flex-wrap: wrap;
		color: #5a6844;
		font-size: 14px;
		font-weight: 600;
	}

	.file-copy .under [lang='zh-Hans'] {
		color: #5a6844;
		font-size: 13px;
	}

	.status,
	.optimized,
	.download {
		flex-wrap: wrap;
	}

	.error {
		max-width: 180px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--accent-dark);
	}

	.saving {
		color: #5d6b45;
		font-size: 12px;
		font-weight: 600;
	}

	.optimized {
		justify-self: end;
		width: max-content;
		white-space: nowrap;
	}

	.download {
		display: inline-flex;
		align-items: center;
		justify-self: start;
		width: fit-content;
		gap: 6px;
		padding: 7px 10px;
		border-radius: 8px;
		background: var(--accent);
		color: var(--on-accent);
		font-size: 13px;
		font-weight: 600;
		text-decoration: none;
	}

	.download svg {
		width: 14px;
		height: 14px;
		flex: 0 0 auto;
	}

	.download [lang='zh-Hans'] {
		color: color-mix(in srgb, var(--on-accent) 78%, transparent);
		font-size: 11px;
	}

	.download:hover {
		background: var(--accent-dark);
		text-decoration: none;
	}

	.remove {
		width: 28px;
		height: 28px;
		flex: 0 0 auto;
		border: 0;
		background: transparent;
		color: var(--muted);
		cursor: pointer;
		font-size: 21px;
		line-height: 1;
	}

	.compress {
		width: calc(100% - 40px);
		margin: 20px;
		padding: 14px 18px;
		border: 0;
		border-radius: 10px;
		background: var(--accent);
		color: var(--on-accent);
		cursor: pointer;
		justify-content: center;
		font-weight: 600;
		transition: 150ms ease;
	}

	.compress [lang='zh-Hans'] {
		color: color-mix(in srgb, var(--on-accent) 72%, transparent);
		font-size: 11px;
	}

	.compress:hover:not(:disabled) {
		background: var(--accent-dark);
		transform: translateY(-1px);
	}

	.compress:disabled {
		cursor: wait;
		opacity: 0.65;
	}
	@media (max-width: 720px) {
		.file-actions {
			flex-wrap: wrap;
			justify-content: flex-end;
		}

		.compress {
			width: calc(100% - 24px);
			margin: 12px;
		}
	}
</style>
