import { json } from '@sveltejs/kit';
import { readChineseRequest, translateChinese } from '$lib/server/chinese';
import type { RequestEvent } from './$types';

export const prerender = false;

export async function POST(event: RequestEvent) {
	const { text } = await readChineseRequest(event);
	const translation = await translateChinese(text);
	return json({ translation }, { headers: { 'cache-control': 'no-store' } });
}
