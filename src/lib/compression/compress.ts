import type { CompressionRequest, CompressionResponse, CompressionSuccess } from './types';

type PendingRequest = {
	resolve: (value: CompressionSuccess) => void;
	reject: (reason: Error) => void;
};

let worker: Worker | undefined;
let nextId = 0;
const pending = new Map<number, PendingRequest>();

function getWorker() {
	if (worker) return worker;

	worker = new Worker(new URL('./compress.worker.ts', import.meta.url), { type: 'module' });
	worker.onmessage = ({ data }: MessageEvent<CompressionResponse>) => {
		const request = pending.get(data.id);
		if (!request) return;
		pending.delete(data.id);

		if (data.ok) request.resolve(data);
		else request.reject(new Error(data.message));
	};
	worker.onerror = (event) => {
		const error = new Error(event.message || 'The compression worker stopped unexpectedly.');
		for (const request of pending.values()) request.reject(error);
		pending.clear();
		worker?.terminate();
		worker = undefined;
	};

	return worker;
}

export function compressImage(file: File, quality: number) {
	return new Promise<CompressionSuccess>((resolve, reject) => {
		const id = nextId++;
		pending.set(id, { resolve, reject });
		const request: CompressionRequest = { id, file, quality };
		getWorker().postMessage(request);
	});
}
