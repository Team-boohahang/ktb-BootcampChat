const getMessageTimestamp = (message) => {
  const timestamp = new Date(message?.timestamp || 0).getTime();
  return Number.isNaN(timestamp) ? 0 : timestamp;
};

export const mergeSortedMessages = (currentMessages, incomingMessages) => {
  if (incomingMessages.length === 0) return currentMessages;
  if (currentMessages.length === 0) return incomingMessages;

  const mergedMessages = new Array(currentMessages.length + incomingMessages.length);
  let currentIndex = 0;
  let incomingIndex = 0;
  let mergedIndex = 0;

  while (
    currentIndex < currentMessages.length &&
    incomingIndex < incomingMessages.length
  ) {
    if (
      getMessageTimestamp(currentMessages[currentIndex]) <=
      getMessageTimestamp(incomingMessages[incomingIndex])
    ) {
      mergedMessages[mergedIndex] = currentMessages[currentIndex];
      currentIndex += 1;
    } else {
      mergedMessages[mergedIndex] = incomingMessages[incomingIndex];
      incomingIndex += 1;
    }
    mergedIndex += 1;
  }

  while (currentIndex < currentMessages.length) {
    mergedMessages[mergedIndex] = currentMessages[currentIndex];
    currentIndex += 1;
    mergedIndex += 1;
  }

  while (incomingIndex < incomingMessages.length) {
    mergedMessages[mergedIndex] = incomingMessages[incomingIndex];
    incomingIndex += 1;
    mergedIndex += 1;
  }

  return mergedMessages;
};

export const mergeIncomingMessages = (currentMessages, incomingMessages) => {
  if (incomingMessages.length === 0) return currentMessages;

  const sortedIncomingMessages = [...incomingMessages].sort(
    (a, b) => getMessageTimestamp(a) - getMessageTimestamp(b)
  );

  return mergeSortedMessages(currentMessages, sortedIncomingMessages);
};

export const deriveUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  if (!Array.isArray(incomingMessages)) {
    throw new Error('Invalid messages format');
  }

  const nextProcessedMessageIds = new Set(processedMessageIds);
  const newMessages = [];

  incomingMessages.forEach((message) => {
    if (!message._id) {
      return;
    }

    if (nextProcessedMessageIds.has(message._id)) {
      return;
    }

    nextProcessedMessageIds.add(message._id);
    newMessages.push(message);
  });

  return {
    messages: mergeIncomingMessages(currentMessages, newMessages),
    processedMessageIds: nextProcessedMessageIds,
  };
};

export const mergeUniqueSortedMessages = (
  currentMessages,
  incomingMessages,
  processedMessageIds
) => {
  return deriveUniqueSortedMessages(
    currentMessages,
    incomingMessages,
    processedMessageIds
  ).messages;
};
