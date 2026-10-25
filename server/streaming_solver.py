"""Incremental JSON-array extraction; only complete validated questions are emitted."""
import json
import re


class QuestionStream:
    def __init__(self):
        self.text=''
        self.position=None
        self.closed=False
        self.count=0

    def feed(self,delta):
        self.text+=delta
        if len(self.text)>1024*1024:raise ValueError('result too large')
        if self.position is None:
            match=re.search(r'"questions"\s*:\s*\[',self.text)
            if not match:return []
            self.position=match.end()
        items=[]
        while not self.closed:
            while self.position<len(self.text) and self.text[self.position] in ' \n\r\t,':self.position+=1
            if self.position==len(self.text):break
            if self.text[self.position]==']':self.closed=True;break
            if self.text[self.position]!='{':raise ValueError('invalid question array')
            try:
                value,end=json.JSONDecoder().raw_decode(self.text,self.position)
            except json.JSONDecodeError:
                break
            self.count+=1
            if self.count>30:raise ValueError('too many questions')
            self.position=end
            items.append(value)
        return items
