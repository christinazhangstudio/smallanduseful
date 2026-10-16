<script lang="ts">
	import favicon from '$lib/assets/favicon.svg';
	import './layout.css';
	import { fly } from 'svelte/transition';
	import { page } from '$app/state';

	let { children } = $props();

	const tabs = [
		{ href: '/', label: 'Home', zh: '首页', color: '#f2dfb7' },
		{ href: '/compress', label: 'Compress', zh: '压缩图片', color: '#d8e5cf' },
		{ href: '/bookmarks', label: 'Bookmarks', zh: '书签', color: '#d4e4eb' },
		{ href: '/notes', label: 'Notes', zh: '便签', color: '#efdbcf' },
		{ href: '/translate', label: 'Chinese → English', zh: '中文译英文', color: '#d0e8e1' },
		{ href: 'https://www.ebay.com/help/call_us?type=ContactUs&topicId=4000', label: 'eBay Support', zh: 'eBay 客服', external: true, color: '#e3d8ee' },
		{ href: 'https://sealift.lystic.dev', label: 'Sealift', zh: 'Sealift', external: true, color: '#f0d8df' }
	];
	let shiftX = $state(0);
	let shiftY = $state(0);
	let hearts = $state<Array<{ id: number; x: number; y: number; drift: number }>>([]);
	let lastX = 0;
	let lastY = 0;
	let lastDirX = 0;
	let lastDirY = 0;
	let flips = 0;
	let lastFlip = 0;
	let nextHeart = 0;
	const sayings = ['多喝点水。', '记得吃蔬菜。', '早点睡。', '累了就休息一会儿。'];
	let saying = $state<string | null>(null);

	$effect(() => {
		const timers: number[] = [];
		const later = (delay: number, run: () => void) => {
			timers.push(window.setTimeout(run, delay));
		};
		const hide = () => {
			saying = null;
			later(20000 + Math.random() * 25000, show);
		};
		const show = () => {
			saying = sayings[Math.floor(Math.random() * sayings.length)];
			later(8000, hide);
		};
		later(2500, show);
		return () => {
			for (const timer of timers) window.clearTimeout(timer);
		};
	});


	function follow(event: PointerEvent) {
		const target = event.currentTarget;
		if (!(target instanceof HTMLElement)) return;
		const box = target.getBoundingClientRect();
		const dx = (event.clientX - box.left - box.width / 2) / (box.width / 2);
		const dy = (event.clientY - box.top - box.height / 2) / (box.height / 2);
		const reach = 36;
		shiftX = Math.max(-1, Math.min(1, -dx)) * reach;
		shiftY = Math.max(-1, Math.min(1, -dy)) * reach;
		const movedX = event.clientX - lastX;
		const movedY = event.clientY - lastY;
		const dirX = Math.abs(movedX) > 8 ? Math.sign(movedX) : 0;
		const dirY = Math.abs(movedY) > 8 ? Math.sign(movedY) : 0;
		const flipped =
			(dirX !== 0 && lastDirX !== 0 && dirX !== lastDirX) ||
			(dirY !== 0 && lastDirY !== 0 && dirY !== lastDirY);
		if (flipped) {
			const now = performance.now();
			flips = now - lastFlip < 260 ? flips + 1 : 1;
			lastFlip = now;
			if (flips >= 3) {
				flips = 0;
				for (let i = 0; i < 3; i += 1) {
					const id = nextHeart++;
					hearts = [
						...hearts,
						{ id, x: event.clientX + (i - 1) * 12, y: event.clientY, drift: (i - 1) * 14 }
					];
					setTimeout(() => {
						hearts = hearts.filter((heart) => heart.id !== id);
					}, 900);
				}
			}
		}
		if (dirX !== 0) lastDirX = dirX;
		if (dirY !== 0) lastDirY = dirY;
		lastX = event.clientX;
		lastY = event.clientY;
	}

	function rest() {
		shiftX = 0;
		shiftY = 0;
		lastDirX = 0;
		lastDirY = 0;
		flips = 0;
	}
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<meta name="theme-color" content="#dccbb6" />
	<meta name="color-scheme" content="light" />
</svelte:head>

<div class="frame">
	<nav class="side" aria-label="Sections">
		<a class="brand" href="/" aria-label="Small Useful Page 小万事页 home">
			<span>Small Useful Page</span>
			<span lang="zh-Hans">小万事页</span>
		</a>
		<ul>
			{#each tabs as tab (tab.href)}
				<li>
					<a
						href={tab.href}
						style:--tab-color={tab.color}
						aria-current={!tab.external && page.url.pathname === tab.href ? 'page' : undefined}
						target={tab.external ? '_blank' : undefined}
						rel={tab.external ? 'external' : undefined}
					>
						<span class="tab-label">
							{tab.label}
							{#if tab.external}
								<svg class="external" viewBox="0 0 16 16" aria-hidden="true">
									<path
										d="M6 3.5H3.5v9h9V10M8.5 3.5H12.5V7.5M12.2 3.8 7 9"
										fill="none"
										stroke="currentColor"
										stroke-width="1.4"
										stroke-linecap="round"
										stroke-linejoin="round"
									/>
								</svg>
							{/if}
						</span>
						<span lang="zh-Hans">{tab.zh}</span>
					</a>
				</li>
			{/each}
		</ul>
	</nav>
	<div class="content">
		{@render children()}
	</div>
</div>
<div class="corner-hit" role="presentation" onpointermove={follow} onpointerleave={rest}>
	<video
		class="corner"
		src="/clips/ai-assistant.webm"
		autoplay
		muted
		loop
		playsinline
		aria-hidden="true"
		tabindex="-1"
		style:transform={`translate(${shiftX}px, ${shiftY}px)`}
	></video>
</div>
{#each hearts as heart (heart.id)}
	<span class="heart" style:left="{heart.x}px" style:top="{heart.y}px" style:--drift="{heart.drift}px">♥</span>
{/each}
{#if saying}
	<p class="speech" lang="zh-Hans" transition:fly={{ x: 16, y: 8, duration: 380 }}>
		<span class="bubble" aria-hidden="true"></span>
		{saying}
	</p>
{/if}

<style>
	.frame {
		position: relative;
		display: grid;
		grid-template-columns: clamp(168px, 16vw, 240px) minmax(0, 1fr);
		min-height: 100vh;
		min-height: 100dvh;
	}

	.side {
		position: sticky;
		z-index: 2;
		top: 0;
		display: flex;
		flex-direction: column;
		gap: clamp(16px, 3vh, 28px);
		height: 100vh;
		height: 100dvh;
		padding: clamp(16px, 2.4vw, 24px) clamp(12px, 1.6vw, 18px);
		border-right: 1px solid var(--line);
		background: var(--paper);
		overflow: auto;
	}

	.brand {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 6px;
		color: var(--ink);
		font-weight: 600;
		letter-spacing: -0.03em;
		text-decoration: none;
	}

	.brand [lang='zh-Hans'] {
		font-size: 11px;
	}

	ul {
		display: flex;
		flex-direction: column;
		gap: 4px;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.side li a {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 6px;
		padding: 8px 10px;
		border-radius: 8px;
		background: var(--tab-color);
		color: var(--ink);
		font-size: 14px;
		font-weight: 500;
		text-decoration: none;
	}

	.tab-label {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}

	.external {
		width: 12px;
		height: 12px;
		flex: 0 0 auto;
	}

	.side li a [lang='zh-Hans'] {
		font-size: 11px;
	}

	.side li a:hover {
		background: color-mix(in srgb, var(--tab-color), var(--ink) 6%);
	}

	.side li a[aria-current='page'] {
		box-shadow: inset 0 0 0 2px var(--ink);
		font-weight: 600;
	}

	.content {
		position: relative;
		z-index: 1;
		min-width: 0;
		--paper: #e7dac6;
		--line: #cebfab;
	}

	.corner-hit {
		position: fixed;
		z-index: 4;
		top: 18px;
		right: 18px;
		width: 120px;
		height: 120px;
	}
	.speech {
		position: fixed;
		z-index: 4;
		top: 28px;
		right: 150px;
		max-width: 180px;
		margin: 0;
		padding: 10px 12px;
		border-radius: 14px;
		background: var(--paper);
		color: var(--ink);
		box-shadow: 0 8px 18px color-mix(in srgb, var(--ink) 12%, transparent);
		font-size: 15px;
		font-weight: 600;
	}

	.bubble {
		position: absolute;
		top: 18px;
		right: -7px;
		width: 14px;
		height: 14px;
		border-radius: 3px;
		background: var(--paper);
		transform: rotate(45deg);
	}

	.corner {
		width: 100%;
		height: 100%;
		border: 0;
		background: transparent;
		object-fit: contain;
		filter: brightness(1.35) contrast(1.3);
		mix-blend-mode: multiply;
		pointer-events: none;
		transition: transform 180ms ease;
	}

	.heart {
		position: fixed;
		z-index: 5;
		margin: -16px 0 0 -8px;
		color: #c45b6a;
		font-size: 18px;
		line-height: 1;
		pointer-events: none;
		animation: rise 900ms ease-out forwards;
	}

	@keyframes rise {
		to {
			transform: translate(var(--drift), -78px) scale(1.2);
			opacity: 0;
		}
	}
	@media (max-width: 860px) {
		.frame {
			grid-template-columns: minmax(0, 1fr);
			grid-template-rows: auto minmax(0, 1fr);
		}

		.side {
			position: sticky;
			z-index: 2;
			height: auto;
			flex-direction: row;
			align-items: center;
			gap: 12px;
			padding: 10px 16px;
			border-right: 0;
			border-bottom: 1px solid var(--line);
		}

		.brand {
			flex: 0 1 auto;
			min-width: 0;
		}

		.brand span:first-child {
			overflow: hidden;
			text-overflow: ellipsis;
			white-space: nowrap;
		}

		ul {
			flex: 1;
			flex-direction: row;
			flex-wrap: wrap;
			justify-content: flex-end;
			min-width: 0;
		}

		.corner-hit {
			top: auto;
			right: 12px;
			bottom: 16px;
			width: 96px;
			height: 96px;
		}

		.speech {
			top: auto;
			right: 118px;
			bottom: 36px;
		}
	}
</style>
