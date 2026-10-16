/// <reference lib="webworker" />

import type { CompressionRequest, CompressionResponse } from './types';

const worker = self as DedicatedWorkerGlobalScope;

worker.onmessage = async ({ data }: MessageEvent<CompressionRequest>) => {
	try {
		const bitmap = await createImageBitmap(data.file, { imageOrientation: 'from-image' });
		const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
		const context = canvas.getContext('2d', { alpha: true });

		if (!context) throw new Error('Canvas is unavailable in this browser.');

		context.drawImage(bitmap, 0, 0);
		bitmap.close();

		const type = data.file.type === 'image/jpeg' ? 'image/jpeg' : 'image/webp';
		const blob = await canvas.convertToBlob({ type, quality: data.quality });
		const response: CompressionResponse = {
			id: data.id,
			ok: true,
			blob,
			width: canvas.width,
			height: canvas.height,
			type
		};
		worker.postMessage(response);
	} catch (error) {
		const response: CompressionResponse = {
			id: data.id,
			ok: false,
			message: error instanceof Error ? error.message : 'Unable to compress this image.'
		};
		worker.postMessage(response);
	}
};

export {};
