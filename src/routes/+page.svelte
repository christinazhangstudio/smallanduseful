<script lang="ts">
	const icons = [
		{ src: '/clips/ai-assistant.mp4', title: 'AI Assistant', zh: 'AI 助手' },
		{ src: '/clips/virtual-assistant.mp4', title: 'Virtual Assistant', zh: '虚拟助手' },
		{ src: '/clips/assistant-ai.mp4', title: 'Assistant AI', zh: '助手 AI' }
	];
</script>

<svelte:head>
	<title>Home · 首页 — Small Useful Page 小万事页</title>
	<meta name="description" content="Small Useful Page. 小万事页。" />
</svelte:head>

<div class="site-shell">
	<main>
		<section class="intro">
			<p class="eyebrow"><span>Small Useful Page</span><span lang="zh-Hans">小万事页</span></p>
			<h1><span>Home</span><span lang="zh-Hans">首页</span></h1>
		</section>

		<div class="icons">
			{#each icons as icon (icon.src)}
				<figure>
					<div class="icon">
						<video src={icon.src} autoplay muted loop playsinline aria-label={icon.title}></video>
					</div>
					<figcaption>
						<span>{icon.title}</span>
						<span lang="zh-Hans">{icon.zh}</span>
					</figcaption>
				</figure>
			{/each}
		</div>
	</main>
</div>

<style>
	.site-shell {
		width: min(100%, 960px);
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
		margin-bottom: 36px;
	}

	.eyebrow {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 6px;
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

	.icons {
		display: flex;
		flex-wrap: wrap;
		gap: clamp(20px, 4vw, 48px);
	}

	figure {
		margin: 0;
		width: min(100%, 180px);
	}

	.icon {
		border: 1px solid var(--line);
		border-radius: 18px;
		background: var(--paper);
		overflow: hidden;
		isolation: isolate;
	}

	video {
		display: block;
		width: 100%;
		aspect-ratio: 1;
		background: transparent;
		object-fit: contain;
		filter: brightness(1.35) contrast(1.3);
		mix-blend-mode: multiply;
	}

	figcaption {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 6px;
		margin-top: 8px;
		font-size: 14px;
		font-weight: 600;
	}

	figcaption [lang='zh-Hans'] {
		font-size: 11px;
	}
</style>
