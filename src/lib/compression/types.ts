export type CompressionRequest = {
	id: number;
	file: File;
	quality: number;
};

export type CompressionSuccess = {
	id: number;
	ok: true;
	blob: Blob;
	width: number;
	height: number;
	type: 'image/jpeg' | 'image/webp';
};

export type CompressionFailure = {
	id: number;
	ok: false;
	message: string;
};

export type CompressionResponse = CompressionSuccess | CompressionFailure;
