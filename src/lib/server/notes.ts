import { env } from '$env/dynamic/private';
import { error } from '@sveltejs/kit';
import { MongoClient, type Collection } from 'mongodb';

export type Note = { id: string; text: string; color: number };

const client = new MongoClient(env.MONGODB_URI || 'mongodb://127.0.0.1:27017', {
	serverSelectionTimeoutMS: 5000
});
let collection: Promise<Collection<Note>> | undefined;

export async function notesCollection() {
	collection ??= (async () => {
		await client.connect();
		const notes = client.db(env.MONGODB_DATABASE || 'smallanduseful').collection<Note>('notes');
		await notes.createIndex({ id: 1 }, { unique: true });
		return notes;
	})().catch((cause) => {
		collection = undefined;
		throw cause;
	});
	try {
		return await collection;
	} catch (cause) {
		console.error('Could not connect to notes storage:', cause);
		error(503, 'Notes storage is unavailable. Please try again.');
	}
}

export async function withNotes<T>(operation: (notes: Collection<Note>) => Promise<T>): Promise<T> {
	const notes = await notesCollection();
	try {
		return await operation(notes);
	} catch (cause) {
		console.error('Notes storage operation failed:', cause);
		error(503, 'Notes storage is unavailable. Please try again.');
	}
}
