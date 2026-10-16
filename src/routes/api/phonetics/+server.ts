import { json } from '@sveltejs/kit';
import { phoneticAlternatives, readChineseRequest, readSelection } from '$lib/server/chinese';
import type { RequestEvent } from './$types';

export const prerender = false;

export async function POST(event: RequestEvent) {
	const value = await readChineseRequest(event);
	const { start, end } = readSelection(value);
	const alternatives = await phoneticAlternatives(value.text, start, end);
	return json({ alternatives }, { headers: { 'cache-control': 'no-store' } });
}
